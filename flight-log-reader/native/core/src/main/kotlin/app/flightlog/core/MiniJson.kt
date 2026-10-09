package app.flightlog.core

/** Минимальный JSON без зависимостей: Map / List / String / Double / Boolean / null. */
object MiniJson {
    fun parse(s: String): Any? = Parser(s).run { val v = value(); ws(); require(i == s.length) { "JSON: лишние символы" }; v }

    fun stringify(v: Any?): String = StringBuilder().also { write(it, v) }.toString()

    private fun write(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> {
                sb.append('"')
                for (c in v) when (c) {
                    '"' -> sb.append("\\\""); '\\' -> sb.append("\\\\"); '\n' -> sb.append("\\n"); '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
                sb.append('"')
            }
            is Number, is Boolean -> sb.append(v.toString())
            is Map<*, *> -> {
                sb.append('{')
                v.entries.forEachIndexed { i, (k, x) -> if (i > 0) sb.append(','); write(sb, k.toString()); sb.append(':'); write(sb, x) }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                v.forEachIndexed { i, x -> if (i > 0) sb.append(','); write(sb, x) }
                sb.append(']')
            }
            else -> write(sb, v.toString())
        }
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "JSON: неожиданный конец" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> { expect("true"); true }
                'f' -> { expect("false"); false }
                'n' -> { expect("null"); null }
                else -> if (c == '-' || c.isDigit()) num() else throw IllegalArgumentException("JSON: символ '$c'")
            }
        }
        fun expect(w: String) { require(s.startsWith(w, i)) { "JSON: ожидалось $w" }; i += w.length }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); expect(":")
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                expect("}"); return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l += value(); ws()
                if (s[i] == ',') { i++; continue }
                expect("]"); return l
            }
        }
        fun str(): String {
            expect("\"")
            val sb = StringBuilder()
            while (true) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> when (val e = s[i++]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r'); 'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C'); 'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                        else -> sb.append(e)
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }
}
