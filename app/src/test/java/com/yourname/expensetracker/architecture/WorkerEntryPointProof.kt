package com.yourname.expensetracker.architecture

/** Bounded source proof, not a Kotlin control-flow graph. Unknown shapes fail closed. */
internal object WorkerEntryPointProof {
    data class Proof(val name: String, val guarded: Boolean)
    private data class Function(val name: String, val body: String, val header: String)
    private val worker = Regex("""\bclass\s+(\w+)\b[^{}]*:\s*(?:androidx\.work\.)?CoroutineWorker\b[^{}]*\{""")
    private val member = Regex("""\bfun\s+(\w+)\s*\([^{}]*\)\s*(?::[^{}=]+)?\s*\{""")
    private val guardType = Regex("""\bval\s+(\w+)\s*:\s*(?:\w+\.)*WorkerExecutionGuard\b""")
    private val assignedCall = Regex("""(?m)^\s*val\s+(\w+)\s*=\s*(?:this\.)?(\w+)\s*\.\s*runGuarded(?:WithContext)?\s*\(""")

    fun inspect(source: String): List<Proof> {
        val masked = WorkerSourceMask.mask(source)
        // In particular, a literal/comment swallowing the rest of a worker is
        // a failed scan, not a worker silently omitted from discovery.
        var depth = 0
        masked.forEach { ch ->
            if (ch == '{') depth++
            if (ch == '}') depth--
            require(depth >= 0) { "WORKER_SOURCE_UNPARSEABLE" }
        }
        require(depth == 0) { "WORKER_SOURCE_UNPARSEABLE" }
        val proofs = worker.findAll(masked).map { declaration ->
            val end = closing(masked, declaration.range.last)
            require(end >= 0) { "WORKER_SOURCE_UNPARSEABLE" }
            val body = masked.substring(declaration.range.last + 1, end)
            val receivers = guardType.findAll(declaration.value).map { it.groupValues[1] }.toSet()
            val functions = member.findAll(body).filter { braceDepth(body, it.range.first) == 0 }.map { match ->
                val functionEnd = closing(body, match.range.last)
                require(functionEnd >= 0) { "WORKER_SOURCE_UNPARSEABLE" }
                Function(
                    match.groupValues[1], body.substring(match.range.last + 1, functionEnd),
                    body.substring(match.range.first, match.range.last)
                )
            }.toList()
            val entry = functions.singleOrNull { it.name == "doWork" }
            Proof(declaration.groupValues[1], entry != null && guardedEntry(entry.body, receivers, functions))
        }.toList()
        // If inheritance was seen but our bounded declaration grammar cannot
        // resolve it, do not shrink the checked surface into an empty success.
        val supertypeCount = Regex(""":\s*(?:androidx\.work\.)?CoroutineWorker\b""").findAll(masked).count()
        require(supertypeCount == proofs.size) { "WORKER_DECLARATION_UNRESOLVED" }
        return proofs
    }

    private fun guardedEntry(body: String, receivers: Set<String>, functions: List<Function>): Boolean =
        assignedCall.findAll(body).any { call ->
            val receiver = call.groupValues[2]
            val prefix = body.substring(0, call.range.first)
            if (receiver !in receivers || braceDepth(body, call.range.first) != 0 ||
                Regex("""\b(?:val|var)\s+${Regex.escape(receiver)}\b""").containsMatchIn(prefix) ||
                Regex("""\b(?:return|throw)\b""").findAll(prefix).any { braceDepth(prefix, it.range.first) == 0 }
            ) return@any false
            if (Regex("""\breturn\b""").findAll(prefix).any { returned ->
                    Regex("""return\s+Result\s*\.\s*failure\s*\(\s*\)""")
                        .find(prefix.substring(returned.range.first))?.range?.first != 0
                }
            ) return@any false
            val returns = Regex("""\breturn\s+""").findAll(body).filter {
                it.range.first > call.range.last && braceDepth(body, it.range.first) == 0
            }.toList()
            returns.size == 1 && bridge(
                body.substring(returns.single().range.last + 1), call.groupValues[1], receivers, functions
            )
        }

    private fun bridge(value: String, result: String, receivers: Set<String>, functions: List<Function>): Boolean {
        val expression = value.trim().trimEnd(';').trim()
        if (Regex("""${Regex.escape(result)}\s*\.\s*toWorkerResult\s*\(\s*\)""").matches(expression)) return true
        val helper = Regex("""(\w+)\([^{};]*\)\s*\.\s*toWorkerResult\s*\(\s*\)""").matchEntire(expression)
        if (helper != null) {
            val function = functions.singleOrNull { it.name == helper.groupValues[1] } ?: return false
            if (receivers.any { receiver ->
                    Regex("""\b${Regex.escape(receiver)}\s*:""").containsMatchIn(function.header)
                }
            ) return false
            val body = function.body.trim()
            return body.startsWith("return ") && directGuard(body.removePrefix("return "), receivers)
        }
        val condition = Regex("""if\s*\(""").find(expression)?.takeIf { it.range.first == 0 } ?: return false
        val endCondition = closing(expression, condition.range.last)
        if (endCondition < 0) return false
        val branches = expression.substring(endCondition + 1).trim()
        if (!branches.startsWith("{")) return false
        val endFirst = closing(branches, 0)
        if (endFirst < 0) return false
        val rest = branches.substring(endFirst + 1).trim()
        if (!rest.startsWith("else")) return false
        val second = rest.removePrefix("else").trim()
        if (!second.startsWith("{") || closing(second, 0) != second.lastIndex) return false
        return bridge(branches.substring(1, endFirst), result, receivers, functions) &&
            bridge(second.substring(1, second.lastIndex), result, receivers, functions)
    }

    private fun directGuard(value: String, receivers: Set<String>): Boolean {
        val expression = value.trim().trimEnd(';').trim()
        val call = Regex("""(?:this\.)?(\w+)\.runGuarded(?:WithContext)?\s*\(""")
            .find(expression)?.takeIf { it.range.first == 0 } ?: return false
        if (call.groupValues[1] !in receivers) return false
        val endArgs = closing(expression, call.range.last)
        if (endArgs < 0) return false
        val tail = expression.substring(endArgs + 1).trim()
        return tail.startsWith("{") && closing(tail, 0) == tail.lastIndex
    }

    private fun braceDepth(text: String, end: Int): Int =
        text.substring(0, end).let { it.count { ch -> ch == '{' } - it.count { ch -> ch == '}' } }

    private fun closing(text: String, start: Int): Int {
        val open = text.getOrNull(start) ?: return -1
        val close = when (open) { '(' -> ')'; '{' -> '}'; else -> return -1 }
        var depth = 0
        for (i in start..text.lastIndex) {
            if (text[i] == open) depth++
            if (text[i] == close && --depth == 0) return i
        }
        return -1
    }
}
