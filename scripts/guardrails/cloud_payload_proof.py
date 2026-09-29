"""Bounded backward proof of policy provenance for provider POST bodies.

This is deliberately not a Kotlin compiler or a whole-program taint analysis.
Only the closed serialization shapes below establish provenance. Unknown
expressions, ambiguous helpers, mutable aliases and unresolved inputs fail
closed. Policy names, comments and unused policy results establish nothing.
The two cloud/privacy guards share this implementation.
"""
from dataclasses import dataclass, field
import re

from kotlin_callable_parser import (
    find_callable_declarations, find_owner_declarations, mask_kotlin_source,
)


class CloudProofError(ValueError):
    def __init__(self):
        super().__init__("CLOUD_PAYLOAD_SOURCE_UNPARSEABLE")


def _literal_end(text, start):
    delimiter = '"""' if text.startswith('"""', start) else text[start]
    i = start + len(delimiter)
    while i < len(text):
        if text.startswith(delimiter, i):
            return i + len(delimiter)
        if delimiter != '"""' and text[i] == "\\":
            i += 2
        elif delimiter != "'" and text.startswith("$" + "{", i):
            i = _group_end(text, i + 1) + 1
        else:
            i += 1
    raise CloudProofError()


def _comment_end(text, start):
    if text.startswith("//", start):
        end = text.find("\n", start)
        return len(text) if end < 0 else end
    depth, i = 1, start + 2
    while i < len(text):
        if text.startswith("/*", i):
            depth += 1
            i += 2
        elif text.startswith("*/", i):
            depth -= 1
            i += 2
            if depth == 0:
                return i
        else:
            i += 1
    raise CloudProofError()


def _group_end(text, start):
    pairs = {"(": ")", "[": "]", "{": "}"}
    stack, i = [pairs[text[start]]], start + 1
    while i < len(text):
        if text.startswith(("//", "/*"), i):
            i = _comment_end(text, i)
        elif text[i] in "\"'":
            i = _literal_end(text, i)
        elif text[i] in pairs:
            stack.append(pairs[text[i]])
            i += 1
        elif text[i] in ")]}":
            if text[i] != stack.pop():
                raise CloudProofError()
            if not stack:
                return i
            i += 1
        else:
            i += 1
    raise CloudProofError()


def _tokens(text):
    """Keep literal boundaries; the shared masker intentionally hides templates."""
    result, i = [], 0
    while i < len(text):
        if text[i].isspace():
            i += 1
        elif text.startswith(("//", "/*"), i):
            i = _comment_end(text, i)
        elif text[i] in "\"'":
            end = _literal_end(text, i)
            result.append((text[i:end], i, end, "literal"))
            i = end
        else:
            match = re.match(r"[A-Za-z_]\w*|\d+(?:\.\d+)?[fFL]?|\?\.|::|!=|==|&&|\|\||\?:|!!|>=|<=|[^\s]", text[i:])
            if not match:
                raise CloudProofError()
            end = i + len(match.group())
            result.append((match.group(), i, end, "code"))
            i = end
    return result


def executable_kotlin_source(source):
    """Blank literal text but retain executable string-template expressions.

    The callable parser's opaque-literal mask is appropriate for declaration
    identity, not violation discovery: Kotlin interpolation can execute a DAO
    write, runCatching, RequestBody.create, or even a POST builder.
    Offsets and line breaks remain identical to the original source.
    """
    try:
        output = list(mask_kotlin_source(source))
        for token, start, _end, kind in _tokens(source):
            if kind != "literal" or token.startswith("'"):
                continue
            delimiter = '"""' if token.startswith('"""') else '"'
            i = len(delimiter)
            while i < len(token) - len(delimiter):
                if delimiter != '"""' and token[i] == "\\":
                    i += 2
                elif token.startswith("$" + "{", i):
                    end = _group_end(token, i + 1)
                    inner = executable_kotlin_source(token[i + 2:end])
                    output[start + i + 2:start + end] = inner
                    i = end + 1
                else:
                    i += 1
        return "".join(output)
    except RecursionError:
        raise CloudProofError() from None


def _expression_end(text, start):
    """End one statement without treating a multiline literal as statements."""
    i = start
    # An initializer may begin on the next line after '=' or after a comment.
    while i < len(text):
        if text[i].isspace():
            i += 1
        elif text.startswith(("//", "/*"), i):
            i = _comment_end(text, i)
        else:
            break
    while i < len(text):
        if text.startswith(("//", "/*"), i):
            i = _comment_end(text, i)
        elif text[i] in "\"'":
            i = _literal_end(text, i)
        elif text[i] in "([{":
            i = _group_end(text, i) + 1
        elif text[i] in ";}":
            return i
        elif text[i] == "\n":
            following = text[i + 1:].lstrip()
            if not (following.startswith((".", "?.")) or re.match(r"(?:else|catch|finally)\b", following)):
                return i
            i += 1
        else:
            i += 1
    return i


def _statements(text):
    i = 0
    while i < len(text):
        if text[i].isspace() or text[i] == ";":
            i += 1
            continue
        if text.startswith(("//", "/*"), i):
            i = _comment_end(text, i)
            continue
        end = _expression_end(text, i)
        if end <= i:
            raise CloudProofError()
        yield text[i:end], i
        i = end


def _split_arguments(text):
    start, i = 0, 0
    while i < len(text):
        if text.startswith(("//", "/*"), i):
            i = _comment_end(text, i)
        elif text[i] in "\"'":
            i = _literal_end(text, i)
        elif text[i] in "([{":
            i = _group_end(text, i) + 1
        elif text[i] == ",":
            yield text[start:i], start
            start, i = i + 1, i + 1
        else:
            i += 1
    if text[start:].strip():
        yield text[start:], start


@dataclass(frozen=True)
class Value:
    kind: str
    prepared: bool = False
    fields: dict = field(default_factory=dict)


BAD = Value("unknown")
CONSTANT = Value("constant")
POLICY = Value("policy")
PREPARED = Value("prepared", True)
POLICY_TYPE = "com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy"


def _join(values, kind="data"):
    values = list(values)
    return BAD if any(v.kind == "unknown" for v in values) else Value(kind, any(v.prepared for v in values))


@dataclass
class Scope:
    declaration: object
    owner: object
    start: int
    end: int
    policies: set


class PayloadProof:
    def __init__(self, source):
        self.source = source
        self.masked = executable_kotlin_source(source)
        self.scopes = []
        self.records = {}
        self.horizons = {}
        self.json_types = set(re.findall(
            r"(?m)^\s*import\s+org\.json\.(JSONObject|JSONArray)\s*$", self.masked
        ))
        if re.search(r"(?m)^\s*import\s+org\.json\.\*\s*$", self.masked):
            self.json_types.update({"JSONObject", "JSONArray"})
        owners = find_owner_declarations(source)
        self.owner_names = {owner.name for owner in owners}
        self.json_types.difference_update(owner.name for owner in owners)
        self.builtin_imports = {
            name for name, qualified in {
                "AppConfig": "com.yourname.expensetracker.domain.config.AppConfig",
                "Base64": "java.util.Base64",
            }.items() if re.search(r"(?m)^\s*import\s+" + re.escape(qualified) + r"\s*$", self.masked)
        }
        self.request_body_extension = bool(re.search(
            r"(?m)^\s*import\s+okhttp3\.RequestBody\.Companion\.toRequestBody\s*$", self.masked
        )) and not re.search(r"\bfun\s+(?:[\w?.]+\s*\.\s*)?toRequestBody\s*\(", self.masked)
        imported_policy = bool(re.search(
            r"(?m)^\s*import\s+com\.yourname\.expensetracker\.domain\.privacy\.(?:CloudPayloadPolicy|\*)\s*$",
            self.masked,
        )) and not any(owner.name == "CloudPayloadPolicy" for owner in owners)
        for match in re.finditer(r"\bdata\s+class\s+(\w+)\s*\(([^{}()]*)\)\s*(?=\n|})", self.masked):
            fields = list(_split_arguments(match.group(2)))
            names = []
            for value, _offset in fields:
                item = re.fullmatch(r"\s*val\s+(\w+)\s*:\s*(?:String|Boolean|Int|Long|Double)\??\s*", value)
                if not item:
                    break
                names.append(item.group(1))
            else:
                # A body or delegation means this is not a proved inert wrapper.
                tail = self.masked[match.end():].lstrip()
                if names and not tail.startswith(("{", ":", "by ")):
                    self.records[match.group(1)] = names
        for owner in owners:
            if not re.search(r"\.\s*post\s*\(", self.masked[owner.body_start:owner.body_end]):
                continue
            header = self.masked[owner.start_offset:owner.body_start]
            policies = {
                name for name, type_name in re.findall(
                    r"\bval\s+(\w+)\s*:\s*((?:\w+\.)*CloudPayloadPolicy)\b", header
                ) if type_name == POLICY_TYPE or (type_name == "CloudPayloadPolicy" and imported_policy)
            }
            for declaration in find_callable_declarations(source, owner, tolerate_unresolved_types=True):
                if declaration.body is None:
                    continue
                start = source.find(declaration.body, declaration.start_offset, declaration.end_offset)
                if start < 0:
                    raise CloudProofError()
                end = start + len(declaration.body)
                if declaration.body.startswith("{"):
                    start, end = start + 1, end - 1
                self.scopes.append(Scope(declaration, owner, start, end, policies))

    def _path(self, scope, at):
        stack = []
        for index in range(scope.start, at):
            char = self.masked[index]
            if char == "{":
                stack.append(index)
            elif char == "}" and stack:
                stack.pop()
        return tuple(stack)

    def parameter_shadow(self, name, scope, at):
        header = self.masked[scope.declaration.start_offset:scope.start]
        if re.search(r"\b" + re.escape(name) + r"\s*:", header):
            return True
        for opening in self._path(scope, at):
            parameters = re.match(r"\s*([^{}\n]*?)\s*->", self.masked[opening + 1:at])
            if parameters and re.search(r"\b" + re.escape(name) + r"\b", parameters.group(1)):
                return True
        return False

    def builtin_shadow(self, name, scope, at, bindings):
        # A proved inert data record is the actual constructor being evaluated,
        # not a counterfeit built-in. Other source-defined types cannot borrow
        # canonical JSON/config/encoder evidence from an import.
        if (name in self.owner_names and name not in self.records) or name in bindings or self.parameter_shadow(name, scope, at):
            return True
        header = self.masked[scope.owner.start_offset:scope.owner.body_start]
        local = self.masked[scope.start:at]
        if re.search(r"\b(?:val|var|fun|class)\s+" + re.escape(name) + r"\b", header + "\n" + local):
            return True
        return any(s.owner.owner == scope.owner.owner and s.declaration.signature.function_name == name
                   for s in self.scopes)

    def json_append_target(self, name, scope, before, bindings, seen):
        """Prove the receiver of a bound put reference without assuming its name.

        Evaluate its immutable initializer, not its later mutations: the latter
        are checked when the target container contributes to a posted body.
        """
        path = self._path(scope, before)
        pattern = re.compile(r"\b(val|var)\s+" + re.escape(name) + r"\b(?:\s*:\s*[^=\n]+)?\s*=")
        definitions = [match for match in pattern.finditer(self.masked, scope.start, before)
                       if path[:len(self._path(scope, match.start()))] == self._path(scope, match.start())]
        if len(definitions) != 1 or definitions[0].group(1) != "val":
            return False
        definition = definitions[0]
        end = _expression_end(self.source, definition.end())
        if end > before:
            return False
        return self.expression(
            self.source[definition.end():end], definition.end(), scope, bindings, seen
        ).kind == "json"

    def json_sink(self, name, scope, at, bindings, seen):
        """Only known JSON constructors/put receivers may retain a live object."""
        if name in self.json_types and not self.builtin_shadow(name, scope, at, bindings):
            return True
        receiver = re.fullmatch(r"(\w+)\.put", name)
        if receiver:
            return self.json_append_target(receiver.group(1), scope, at, bindings, seen)
        if name != "put" or self.builtin_shadow("put", scope, at, bindings):
            return False
        # Closed direct chains used by the providers, not arbitrary factory().put.
        prefixes = [(self.masked[scope.start:at], r"\s*\.\s*$")]
        prefixes.extend((self.masked[scope.start:opening], r"\s*\.\s*apply\s*$")
                        for opening in self._path(scope, at))
        for prefix, suffix in prefixes:
            constructor = re.search(r"\b(JSONObject|JSONArray)\s*\(\s*\)" + suffix, prefix)
            if constructor and constructor.group(1) in self.json_types and not self.builtin_shadow(
                constructor.group(1), scope, at, bindings
            ):
                return True
        return False

    def resolve(self, name, scope, at, bindings, seen):
        key = (scope.start, name, at)
        if key in seen or len(seen) > 48:
            return BAD
        seen = seen | {key}
        target_path = self._path(scope, at)
        definitions = []
        pattern = re.compile(r"\b(val|var)\s+" + re.escape(name) + r"\b(?:\s*:\s*[^=\n]+)?\s*=")
        for match in pattern.finditer(self.masked, scope.start, at):
            path = self._path(scope, match.start())
            if target_path[:len(path)] == path:
                definitions.append(match)
        if not definitions:
            if name in bindings:
                return bindings[name]
            if self.parameter_shadow(name, scope, at):
                return BAD
            return POLICY if name in scope.policies else BAD
        if len(definitions) != 1 or definitions[0].group(1) != "val":
            return BAD
        definition = definitions[0]
        end = _expression_end(self.source, definition.end())
        if end > at:
            return BAD
        value = self.expression(self.source[definition.end():end], definition.end(), scope, bindings, seen)
        # A JSON container can retain references to another mutable container.
        # Inspect those dependencies through the consuming POST/helper return,
        # not just through the earlier constructor that captured the reference.
        observation = min(scope.end, max(at, self.horizons.get(scope.start, at)))
        intervening = self.masked[end:observation]
        if re.search(r"(?m)^\s*" + re.escape(name) + r"\s*=(?!=)", intervening):
            return BAD
        if value.kind != "json":
            return value
        # Mutable container aliases/escapes are not silently treated as strings.
        if re.search(r"\b(?:val|var)\s+\w+(?:\s*:\s*[^=\n]+)?\s*=\s*\(*\s*"
                     + re.escape(name) + r"\s*\)*\s*(?:;|\n|$)", intervening):
            return BAD
        for mutation in re.finditer(r"\b" + re.escape(name) + r"\s*(?:\?\.|\.)\s*(\w+)\s*(?=[({])", intervening):
            method = mutation.group(1)
            if method in {"toString", "toRequestBody"}:
                continue
            opening = end + mutation.end()
            closing = _group_end(self.source, opening)
            if method == "let":
                target = re.fullmatch(r"\s*(\w+)\s*::\s*put\s*", self.masked[opening + 1:closing])
                if not target or not self.json_append_target(
                    target.group(1), scope, end + mutation.start(), bindings, seen
                ):
                    return BAD
                continue
            if method != "put":
                return BAD
            args = self.arguments(self.source[opening + 1:closing], opening + 1, scope, bindings, seen)
            value = _join([value] + [v for _n, v in args], "json")
        references = list(re.finditer(r"\b" + re.escape(name) + r"\s*::", intervening))
        appenders = list(re.finditer(r"\b(\w+)\?\.let\(\s*" + re.escape(name) + r"\s*::\s*put\s*\)", intervening))
        if len(references) != len(appenders):
            return BAD
        for appender in appenders:
            value = _join([value, self.resolve(appender.group(1), scope, end + appender.start(), bindings, seen)], "json")
        # Passing a live JSON object to an unknown function is an unproved escape.
        for call in re.finditer(r"\b([\w.]+)\s*\(", intervening):
            opening = end + call.end() - 1
            closing = _group_end(self.source, opening)
            argument_text = self.masked[opening + 1:closing]
            # A serialized snapshot or a null comparison is not a live-object
            # escape. Nested unknown calls still have their own argument scan.
            argument_text = re.sub(
                r"\b" + re.escape(name) + r"\s*(?:\?\.|\.)\s*toString\s*\(\s*\)",
                "serializedValue", argument_text,
            )
            argument_text = re.sub(
                r"\b" + re.escape(name) + r"\s*(?:!=|==)\s*null\b", "true", argument_text
            )
            if not re.search(r"\b" + re.escape(name) + r"\b", argument_text):
                continue
            if not self.json_sink(call.group(1), scope, end + call.start(), bindings, seen) and not any(
                end + app.start() <= opening <= end + app.end() for app in appenders
            ):
                return BAD
        return value

    def arguments(self, text, start, scope, bindings, seen):
        values = []
        for raw, offset in _split_arguments(text):
            named = re.match(r"\s*(\w+)\s*=(?!=)", raw)
            name = named.group(1) if named else None
            shift = named.end() if named else 0
            values.append((name, self.expression(raw[shift:], start + offset + shift, scope, bindings, seen)))
        return values

    def helper(self, name, args, scope, seen):
        # The proof does not model cross-call mutation of live JSON parameters.
        # Immutable prepared payloads and serialized strings are supported.
        if any(value.kind == "json" for _name, value in args):
            return BAD
        candidates = [s for s in self.scopes if s.owner.owner == scope.owner.owner
                      and s.declaration.signature.function_name == name]
        if len(candidates) != 1 or len(seen) > 48:
            return BAD
        target = candidates[0]
        helper_key = (target.start, "helper", target.end)
        if helper_key in seen:
            return BAD
        header = self.source[target.declaration.start_offset:target.start]
        function = re.search(r"\bfun\s+" + re.escape(name) + r"\s*\(", header)
        if not function:
            return BAD
        closing = _group_end(header, function.end() - 1)
        parameters = []
        for raw, _offset in _split_arguments(header[function.end():closing]):
            match = re.match(r"\s*(\w+)\s*:", raw)
            if not match:
                return BAD
            parameters.append(match.group(1))
        if len(parameters) != len(args):
            return BAD
        bindings = {}
        for index, (argument_name, value) in enumerate(args):
            parameter = argument_name or parameters[index]
            if parameter not in parameters or parameter in bindings:
                return BAD
            bindings[parameter] = value
        returns = list(re.finditer(r"\breturn(?=\s|@)", self.masked[target.start:target.end]))
        if len(returns) != 1 or self._path(target, target.start + returns[0].start()):
            return BAD
        start = target.start + returns[0].end()
        if self.source[start:start + 1] == "@":
            return BAD
        # Masked literal characters are spaces too. Skip only real whitespace,
        # otherwise a quoted/template prefix disappears from the expression.
        while start < target.end and self.source[start].isspace():
            start += 1
        end = _expression_end(self.source, start)
        if self.source[end:target.end].strip(" \r\n\t;"):
            return BAD
        previous_horizon = self.horizons.get(target.start)
        self.horizons[target.start] = target.end
        try:
            return self.expression(self.source[start:end], start, target, bindings, seen | {helper_key})
        finally:
            if previous_horizon is None:
                self.horizons.pop(target.start, None)
            else:
                self.horizons[target.start] = previous_horizon

    def literal(self, token, start, scope, bindings, seen):
        delimiter = '"""' if token.startswith('"""') else token[0]
        values, i = [], len(delimiter)
        while i < len(token) - len(delimiter):
            if delimiter != '"""' and token[i] == "\\":
                i += 2
            elif delimiter != "'" and token.startswith("$" + "{", i):
                end = _group_end(token, i + 1)
                values.append(self.expression(token[i + 2:end], start + i + 2, scope, bindings, seen))
                i = end + 1
            elif delimiter != "'" and token[i] == "$":
                match = re.match(r"[A-Za-z_]\w*", token[i + 1:])
                if match:
                    values.append(self.resolve(match.group(), scope, start + i, bindings, seen))
                    i += len(match.group()) + 1
                else:
                    i += 1
            else:
                i += 1
        return _join(values)

    def block_value(self, text, start, scope, bindings, seen):
        statements = list(_statements(text))
        if not statements:
            return BAD
        last, offset = statements[-1]
        return self.expression(last, start + offset, scope, bindings, seen)

    def expression(self, text, start, scope, bindings, seen):
        if len(seen) > 48:
            return BAD
        # These are fixed configuration scalars, not caller-controlled inputs.
        if re.fullmatch(r"\s*AppConfig\.Ai\.[A-Z][A-Z_0-9]*(?:\.toDouble\(\))?\s*", text) and "AppConfig" in self.builtin_imports and not self.builtin_shadow("AppConfig", scope, start, bindings):
            return CONSTANT
        encoded = re.match(r"\s*(java\.util\.)?Base64\.getEncoder\(\)\.encodeToString\s*\(", text)
        if encoded:
            root = "java" if encoded.group(1) else "Base64"
            if self.builtin_shadow(root, scope, start, bindings) or (
                not encoded.group(1) and "Base64" not in self.builtin_imports
            ):
                return BAD
            end = _group_end(text, encoded.end() - 1)
            if text[end + 1:].strip():
                return BAD
            return self.expression(text[encoded.end():end], start + encoded.end(), scope, bindings, seen)
        parser = Expression(self, text, start, scope, bindings, seen)
        value = parser.parse()
        return value if parser.index == len(parser.tokens) else BAD

    def unproved_posts(self):
        failures = []
        for post in re.finditer(r"\.\s*post\s*\(", self.masked):
            candidates = [s for s in self.scopes if s.start <= post.start() < s.end]
            if not candidates:
                failures.append(self.source.count("\n", 0, post.start()) + 1)
                continue
            scope = min(candidates, key=lambda s: s.end - s.start)
            end = _group_end(self.source, post.end() - 1)
            self.horizons = {scope.start: end}
            value = self.expression(self.source[post.end():end], post.end(), scope, {}, set())
            if value.kind == "unknown" or not value.prepared:
                failures.append(self.source.count("\n", 0, post.start()) + 1)
        return failures


class Expression:
    def __init__(self, proof, text, start, scope, bindings, seen):
        self.proof, self.text, self.start = proof, text, start
        self.scope, self.bindings, self.seen = scope, bindings, seen
        self.tokens, self.index = _tokens(text), 0

    def peek(self):
        return self.tokens[self.index][0] if self.index < len(self.tokens) else ""

    def group(self, opening="("):
        if self.peek() != opening:
            raise CloudProofError()
        token = self.tokens[self.index]
        end = _group_end(self.text, token[1])
        self.index += 1
        while self.index < len(self.tokens) and self.tokens[self.index][1] <= end:
            self.index += 1
        return self.text[token[2]:end], self.start + token[2]

    def evaluate(self, text, start):
        return self.proof.expression(text, start, self.scope, self.bindings, self.seen)

    def args(self):
        text, start = self.group()
        return self.proof.arguments(text, start, self.scope, self.bindings, self.seen)

    def parse(self):
        if not self.peek():
            return BAD
        token = self.tokens[self.index]
        self.index += 1
        name = token[0]
        if token[3] == "literal":
            value = self.proof.literal(name, self.start + token[1], self.scope, self.bindings, self.seen)
        elif name in {"null", "true", "false"} or re.fullmatch(r"\d+(?:\.\d+)?[fFL]?", name):
            value = CONSTANT
        elif name in {"!", "-", "+"}:
            value = self.parse()
        elif name == "(":
            self.index -= 1
            value = self.evaluate(*self.group())
        elif name == "if":
            condition = self.evaluate(*self.group())
            first = self.proof.block_value(*self.group("{"), self.scope, self.bindings, self.seen)
            if self.peek() != "else":
                return BAD
            self.index += 1
            second = self.proof.block_value(*self.group("{"), self.scope, self.bindings, self.seen)
            combined = _join([condition, first, second])
            value = BAD if combined.kind == "unknown" else Value(
                "json" if "json" in {first.kind, second.kind} else "data",
                first.prepared and second.prepared,
            )
        elif name == "try":
            value = self.proof.block_value(*self.group("{"), self.scope, self.bindings, self.seen)
            catches = 0
            while self.peek() == "catch":
                self.index += 1
                self.group()
                body, _start = self.group("{")
                statements = list(_statements(body))
                if not statements or not re.match(r"(?:return|throw)\b", statements[-1][0].lstrip()):
                    return BAD
                catches += 1
            if not catches or self.peek() == "finally":
                return BAD
        elif self.peek() == "(":
            args = self.args()
            if name in {"JSONObject", "JSONArray"} and name in self.proof.json_types and not self.proof.builtin_shadow(name, self.scope, self.start + token[1], self.bindings):
                value = _join([v for _n, v in args], "json")
            elif name in self.proof.records and not self.proof.builtin_shadow(name, self.scope, self.start + token[1], self.bindings):
                names = self.proof.records[name]
                if len(args) != len(names):
                    return BAD
                fields = {}
                for index, (key, item) in enumerate(args):
                    key = key or names[index]
                    if key not in names or key in fields:
                        return BAD
                    fields[key] = item
                value = BAD if any(v.kind == "unknown" for v in fields.values()) else Value(
                    "record", any(v.prepared for v in fields.values()), fields
                )
            else:
                value = self.proof.helper(name, args, self.scope, self.seen)
        else:
            value = self.proof.resolve(name, self.scope, self.start + token[1], self.bindings, self.seen)
        while self.peek() in {".", "?."}:
            self.index += 1
            member = self.peek()
            if not member:
                return BAD
            self.index += 1
            if member == "apply" and self.peek() == "{" and value.kind == "json":
                body, start = self.group("{")
                for statement, offset in _statements(body):
                    match = re.match(r"\s*(?:this\.)?put\s*\(", statement)
                    if not match:
                        return BAD
                    end = _group_end(statement, match.end() - 1)
                    if statement[end + 1:].strip():
                        return BAD
                    args = self.proof.arguments(statement[match.end():end], start + offset + match.end(), self.scope, self.bindings, self.seen)
                    value = _join([value] + [v for _n, v in args], "json")
            elif self.peek() == "(":
                raw_args, argument_start = self.group()
                if member in {"prepareText", "prepareReceiptAssist", "prepareBankStatementValidation"} and value.kind == "policy":
                    value = PREPARED
                elif member == "toRequestBody" and value.kind in {"data", "constant"} and self.proof.request_body_extension:
                    # Its optional argument is media metadata, not serialized content.
                    value = _join([value])
                elif member in {"toString", "toDouble"} and not raw_args.strip() and value.kind not in {"unknown", "policy", "prepared"}:
                    value = _join([value])
                elif member == "put" and value.kind == "json":
                    args = self.proof.arguments(raw_args, argument_start, self.scope, self.bindings, self.seen)
                    value = _join([value] + [v for _n, v in args], "json")
                else:
                    return BAD
            elif value.kind == "prepared" and member in {"text", "imageBytes", "imageMimeType", "rawImageIncluded", "rawTextIncluded", "redactionApplied"}:
                value = Value("data", True)
            elif value.kind == "record" and member in value.fields:
                value = value.fields[member]
            else:
                return BAD
        if self.peek() in {"+", "-", "*", "/", "==", "!=", "&&", "||", ">", "<", ">=", "<="}:
            self.index += 1
            value = _join([value, self.parse()])
        return value


def unproved_post_lines(source):
    """Return one-based source lines; never report compliant on a failed parse."""
    try:
        return PayloadProof(source).unproved_posts()
    except RecursionError:
        raise CloudProofError() from None
