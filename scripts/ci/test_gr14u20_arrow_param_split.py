"""GR-14u20: a Kotlin arrow (`->`) is not a generic close bracket.

The parameter-list comma splitter in `_build` tracked bracket depth over
`"(<["` / `")>]"`.  In a function type like `() -> Unit` the arrow's `>` was
counted as a CLOSING bracket, driving the depth to -1, so no later comma ever
sat at depth 0 and the entire parameter list collapsed into ONE unnamed piece.
With no parameter names recorded, `receiver_fqcn_for_call` could never match a
parameter receiver, so `viewModel.<member>()` in every Compose screen fell back
to name-matching and stayed an uncertain edge.

`_skip_return_type` already carries the correct guard ("Kotlin arrow (`->`):
not a generic close bracket"); the splitters never got it.
"""
from __future__ import annotations

from scripts.ci.inspect_db_mediation_proof import _production_contract
from scripts.db_guard.mediation_analysis.callgraph import CallGraphBuilder

_VIEWMODEL = "app/src/main/java/com/example/AddExpenseViewModel.kt"
_SCREEN = "app/src/main/java/com/example/Screen.kt"


def _build(files: dict[str, str]) -> CallGraphBuilder:
    builder = CallGraphBuilder(_production_contract(), files)
    builder.build()
    return builder


def _only_callable(builder: CallGraphBuilder, path: str):
    keys = [k for k in builder.callables if k.split("|")[0] == path]
    assert len(keys) == 1, keys
    return keys[0], builder.callables[keys[0]]


def _params(files: dict[str, str], path: str) -> list[tuple[str, str]]:
    builder = _build(files)
    _key, model = _only_callable(builder, path)
    return list(model.params_named)


class TestArrowAwareParameterSplit:
    def test_lambda_typed_parameter_does_not_swallow_later_parameters(self):
        """The AddExpenseSheet shape: a lambda param first, more params after."""
        files = {
            _SCREEN: (
                "package com.example\n\n"
                "fun Screen(\n"
                "    onDismiss: () -> Unit,\n"
                "    amount: String? = null,\n"
                "    viewModel: AddExpenseViewModel = make(),\n"
                ") {\n"
                "}\n"
            )
        }
        params = _params(files, _SCREEN)
        names = [n for n, _t in params]
        assert names == ["onDismiss", "amount", "viewModel"], params
        assert params[0][1] == "() -> Unit"
        assert params[2][1].startswith("AddExpenseViewModel")

    def test_two_arrows_in_one_parameter_list(self):
        files = {
            _SCREEN: (
                "package com.example\n\n"
                "fun Screen(a: () -> Unit, b: (Int) -> String, c: Int) {\n"
                "}\n"
            )
        }
        names = [n for n, _t in _params(files, _SCREEN)]
        assert names == ["a", "b", "c"]

    def test_nested_function_type_arrows(self):
        files = {
            _SCREEN: (
                "package com.example\n\n"
                "fun Screen(cb: (Int) -> (String) -> Unit, tail: Int) {\n"
                "}\n"
            )
        }
        params = _params(files, _SCREEN)
        assert [n for n, _t in params] == ["cb", "tail"]

    def test_generic_and_lambda_spellings_together(self):
        """Regression guard: real brackets and arrows must both balance."""
        files = {
            _SCREEN: (
                "package com.example\n\n"
                "fun Screen(items: List<Map<String, Int>>, cb: () -> Unit, n: Int) {\n"
                "}\n"
            )
        }
        params = _params(files, _SCREEN)
        assert [n for n, _t in params] == ["items", "cb", "n"]
        assert params[0][1] == "List<Map<String, Int>>"

    def test_parameter_receiver_resolves_to_its_declared_type(self):
        """The payoff: `viewModel.save()` resolves exactly, not by name match."""
        files = {
            _VIEWMODEL: (
                "package com.example\n\n"
                "class AddExpenseViewModel {\n"
                "    fun save() {}\n"
                "}\n"
            ),
            _SCREEN: (
                "package com.example\n\n"
                "fun Screen(\n"
                "    onDismiss: () -> Unit,\n"
                "    viewModel: AddExpenseViewModel = make(),\n"
                ") {\n"
                "    viewModel.save()\n"
                "}\n"
            ),
        }
        builder = _build(files)
        key, _model = _only_callable(builder, _SCREEN)
        calls = [c for c in builder.calls_by_callable.get(key, ()) if c.name == "save"]
        assert calls, "save() was not recorded"
        fqcn, known = builder.receiver_fqcn_for_call(calls[0])
        assert known is True, "viewModel receiver stayed unknown"
        assert fqcn == "com.example.AddExpenseViewModel"
