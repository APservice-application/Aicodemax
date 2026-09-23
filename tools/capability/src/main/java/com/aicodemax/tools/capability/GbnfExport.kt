package com.aicodemax.tools.capability

/**
 * CP-130 (spec Phase 14 + §14): GBNF grammar constraining local-model
 * output to valid tool calls — { "tool": "<known-id>", "params": {...} }.
 * Consumed by llama.cpp constrained sampling (JNI wiring later).
 */
object GbnfExport {
    fun toolCallGrammar(bindings: List<CapabilityBinding>): String {
        val ids = bindings.map { it.capabilityId }.distinct().sorted()
        require(ids.isNotEmpty()) { "need at least one binding" }
        val alternatives = ids.joinToString(" | ") { "\"$it\"" }
        return """
            |root      ::= "{" ws "\"tool\"" ws ":" ws tool-name ws "," ws "\"params\"" ws ":" ws value ws "}"
            |tool-name ::= "\"" ($alternatives) "\""
            |value     ::= object | array | string | number | ("true" | "false" | "null") ws
            |object    ::= "{" ws ( string ws ":" ws value ( ws "," ws string ws ":" ws value )* )? ws "}"
            |array     ::= "[" ws ( value ( ws "," ws value )* )? ws "]"
            |string    ::= "\"" ( [^"\\\x7F\x00-\x1F] | "\\" ["\\/bfnrt] | "u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] )* "\"" ws
            |number    ::= ("-"? ( "0" | [1-9] [0-9]* ) ( "." [0-9]+ )? ( [eE] [-+]? [0-9]+ )? ) ws
            |ws        ::= ([ \t\n] ws)?
            |
        """.trimMargin()
    }
}
