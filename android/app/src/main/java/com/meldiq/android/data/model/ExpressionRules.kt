package com.meldiq.android.data.model

/**
 * Batch B9 (matrix EXP-04/05): a community's own emoji and stickers in messages. The text keeps `:name:`, so older
 * apps and people outside the community read the name; the references next to it (inside the encryption) say which
 * image, and its hash.
 */
object ExpressionRules {
    val NAME = Regex("^[a-z0-9_]{2,32}$")
    private val ID = Regex("^(emj|stk)_[A-Za-z0-9_-]{1,40}$")
    private val ORG = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val SHA = Regex("^[0-9a-f]{64}$")
    const val MAX_REFS = 20
    const val MAX_BYTES = 256 * 1024
    private val TOKEN = Regex(":([a-z0-9_]{2,32}):")

    fun cleanRef(r: CustomEmojiRef?): CustomEmojiRef? =
        r?.takeIf { ID.matches(it.id) && ORG.matches(it.orgId) && NAME.matches(it.name) && SHA.matches(it.sha256) }

    /** Well-formed references whose `:name:` really is in [text], one per name, at most [MAX_REFS]. */
    fun cleanRefs(refs: List<CustomEmojiRef>?, text: String): List<CustomEmojiRef> {
        val names = TOKEN.findAll(text).map { it.groupValues[1] }.toSet()
        return refs.orEmpty().mapNotNull(::cleanRef).filter { it.name in names }.distinctBy { it.name }.take(MAX_REFS)
    }

    data class Token(val start: Int, val end: Int, val ref: CustomEmojiRef)

    /** Where each referenced `:name:` sits in [text] (every occurrence). */
    fun tokens(text: String, refs: List<CustomEmojiRef>): List<Token> {
        if (refs.isEmpty()) return emptyList()
        val byName = refs.associateBy { it.name }
        return TOKEN.findAll(text).mapNotNull { m -> byName[m.groupValues[1]]?.let { Token(m.range.first, m.range.last + 1, it) } }.toList()
    }

    /** The references a message being sent needs: the picked emoji whose `:name:` is still in the text. */
    fun forSending(text: String, picked: Collection<CustomEmojiRef>): List<CustomEmojiRef> = cleanRefs(picked.toList(), text)

    /** A name as typed for an upload: lowercase, spaces and dashes to `_`, other characters dropped. */
    fun suggestName(raw: String): String =
        raw.substringBeforeLast('.').lowercase().replace(Regex("[\\s-]+"), "_").replace(Regex("[^a-z0-9_]"), "").take(32)

    /** Whether [hex] is the SHA-256 of [bytes] (an image is shown only when it is). */
    fun matches(bytes: ByteArray, hex: String): Boolean =
        java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == hex
}
