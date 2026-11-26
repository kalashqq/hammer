package hammer.vmf

/** Generic VMF block. Property order and duplicate keys are preserved, so unknown data survives a round-trip. */
class VmfNode(val name: String) {
    val props = mutableListOf<Pair<String, String>>()
    val children = mutableListOf<VmfNode>()

    operator fun get(key: String): String? = props.firstOrNull { it.first.equals(key, true) }?.second
    operator fun set(key: String, value: String) {
        val i = props.indexOfFirst { it.first.equals(key, true) }
        if (i >= 0) props[i] = props[i].first to value else props.add(key to value)
    }
    fun child(n: String): VmfNode? = children.firstOrNull { it.name.equals(n, true) }
    fun all(n: String): List<VmfNode> = children.filter { it.name.equals(n, true) }
    fun add(node: VmfNode): VmfNode = node.also { children.add(it) }
}

class VmfParseException(msg: String) : Exception(msg)

object VmfParser {
    private sealed interface Tok {
        data class Str(val s: String) : Tok
        data class Word(val s: String) : Tok
        data object Open : Tok
        data object Close : Tok
    }

    private fun tokenize(text: String): List<Tok> {
        val out = ArrayList<Tok>(); var i = 0; val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c.isWhitespace() || c == '\uFEFF' -> i++
                c == '/' && i + 1 < n && text[i + 1] == '/' -> { while (i < n && text[i] != '\n') i++ }
                c == '{' -> { out += Tok.Open; i++ }
                c == '}' -> { out += Tok.Close; i++ }
                c == '"' -> {
                    val end = text.indexOf('"', i + 1)
                    if (end < 0) throw VmfParseException("Unterminated string at $i")
                    out += Tok.Str(text.substring(i + 1, end)); i = end + 1
                }
                else -> {
                    val s = i
                    while (i < n && !text[i].isWhitespace() && text[i] !in "{}\"") i++
                    out += Tok.Word(text.substring(s, i))
                }
            }
        }
        return out
    }

    /** Returns a synthetic root whose children are the top-level blocks (versioninfo, world, entity, cameras, cordons...). */
    fun parse(text: String): VmfNode {
        val toks = tokenize(text); var p = 0
        fun block(node: VmfNode) {
            while (true) {
                if (p >= toks.size) throw VmfParseException("Unexpected end of file inside '${node.name}'")
                when (val t = toks[p++]) {
                    Tok.Close -> return
                    Tok.Open -> throw VmfParseException("Unexpected '{' in '${node.name}'")
                    is Tok.Str -> if (toks.getOrNull(p) == Tok.Open) { p++; block(node.add(VmfNode(t.s))) }
                        else {
                            val v = toks.getOrNull(p++) as? Tok.Str ?: throw VmfParseException("Key '${t.s}' has no value")
                            node.props += t.s to v.s
                        }
                    is Tok.Word -> {
                        if (toks.getOrNull(p++) != Tok.Open) throw VmfParseException("Expected '{' after '${t.s}'")
                        block(node.add(VmfNode(t.s)))
                    }
                }
            }
        }
        val root = VmfNode("")
        while (p < toks.size) {
            val t = toks[p++]
            val name = (t as? Tok.Word)?.s ?: (t as? Tok.Str)?.s ?: throw VmfParseException("Unexpected token at top level")
            if (toks.getOrNull(p++) != Tok.Open) throw VmfParseException("Expected '{' after '$name'")
            block(root.add(VmfNode(name)))
        }
        return root
    }
}

object VmfWriter {
    fun write(root: VmfNode): String = StringBuilder().also { sb -> root.children.forEach { emit(it, 0, sb) } }.toString()
    private fun emit(n: VmfNode, d: Int, sb: StringBuilder) {
        val tabs = "\t".repeat(d)
        sb.append(tabs).append(n.name).append('\n').append(tabs).append("{\n")
        n.props.forEach { (k, v) -> sb.append(tabs).append("\t\"").append(k).append("\" \"").append(v).append("\"\n") }
        n.children.forEach { emit(it, d + 1, sb) }
        sb.append(tabs).append("}\n")
    }
}
