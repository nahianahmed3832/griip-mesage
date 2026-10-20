package com.meldiq.android.ui.screens.workspace.documents

/**
 * Document rules for the app (round 7 slice C4, docs/DOCUMENTS.md); pure, unit-tested in `DocRulesTest`: roles, and
 * the small Markdown subset documents are shown with (headings, lists, checkboxes, quotes, bold, inline code).
 */
object DocRules {
    private val RANK = mapOf("view" to 0, "comment" to 1, "edit" to 2, "admin" to 3)

    fun atLeast(role: String?, needed: String): Boolean = (RANK[role] ?: -1) >= (RANK[needed] ?: 99)

    val ACCESS = listOf("workspace", "channel", "restricted")
    val DEFAULT_ROLES = listOf("view", "comment", "edit")
    val MEMBER_ROLES = listOf("view", "comment", "edit", "admin")
    const val MAX_TITLE = 120
    const val MAX_CONTENT = 200_000

    sealed interface Block {
        data class Heading(val level: Int, val text: String) : Block
        data class Bullet(val text: String, val checked: Boolean? = null) : Block
        data class Numbered(val number: String, val text: String) : Block
        data class Quote(val text: String) : Block
        data class Paragraph(val text: String) : Block
        data object Gap : Block
    }

    private val HEADING = Regex("^(#{1,3})\\s+(.*)$")
    private val CHECK = Regex("^[-*]\\s+\\[( |x|X)]\\s+(.*)$")
    private val BULLET = Regex("^[-*]\\s+(.*)$")
    private val NUMBERED = Regex("^(\\d{1,3})[.)]\\s+(.*)$")

    /** Line-based: each line is one block; consecutive blank lines collapse into one gap. */
    fun parse(markdown: String): List<Block> {
        val out = mutableListOf<Block>()
        for (raw in markdown.lines()) {
            val line = raw.trimEnd()
            val b: Block = if (line.isBlank()) Block.Gap
            else HEADING.find(line)?.destructured?.let { (h, t) -> Block.Heading(h.length, t) }
                ?: CHECK.find(line)?.destructured?.let { (x, t) -> Block.Bullet(t, x.isNotBlank()) }
                ?: BULLET.find(line)?.let { Block.Bullet(it.groupValues[1]) }
                ?: NUMBERED.find(line)?.destructured?.let { (n, t) -> Block.Numbered(n, t) }
                ?: if (line.startsWith(">")) Block.Quote(line.removePrefix(">").trim()) else Block.Paragraph(line)
            if (b == Block.Gap && (out.isEmpty() || out.last() == Block.Gap)) continue
            out += b
        }
        return out.dropLastWhile { it == Block.Gap }
    }

    /** Inline spans: (text, bold, code). `**bold**` and `` `code` ``; anything else is plain. */
    fun inline(text: String): List<Triple<String, Boolean, Boolean>> {
        val out = mutableListOf<Triple<String, Boolean, Boolean>>()
        val re = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`")
        var at = 0
        for (m in re.findAll(text)) {
            if (m.range.first > at) out += Triple(text.substring(at, m.range.first), false, false)
            val bold = m.groupValues[1]
            if (bold.isNotEmpty()) out += Triple(bold, true, false) else out += Triple(m.groupValues[2], false, true)
            at = m.range.last + 1
        }
        if (at < text.length) out += Triple(text.substring(at), false, false)
        return out
    }
}
