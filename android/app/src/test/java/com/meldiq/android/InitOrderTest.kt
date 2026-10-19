package com.meldiq.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Regression guard for the 2026-10-17 chat crash: `ConversationViewModel`'s `init` block launched a coroutine on
 * `Dispatchers.Main.immediate` that wrote `maxFile`, a property declared further down the class. The coroutine started
 * inside the constructor, read `maxFile` while it was still null, and crashed with a NullPointerException when it
 * resumed, so every chat crashed when opened.
 *
 * Kotlin initialises properties and runs `init` blocks in declaration order, so an `init` block must not touch a
 * property (with a backing field or a delegate) declared after it. This test checks every class in the app.
 */
class InitOrderTest {

    @Test
    fun noInitBlockUsesAPropertyDeclaredAfterIt() {
        val src = File("src/main/java")
        assertTrue("run from the app module (src/main/java not found in ${File(".").absolutePath})", src.isDirectory)
        val problems = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            InitOrder.violations(f.readText()).map { "${f.relativeTo(src)}: $it" }
        }.toList()
        assertEquals("init blocks using properties declared after them:\n" + problems.joinToString("\n"), emptyList<String>(), problems)
    }

    @Test
    fun theCheckFindsTheOriginalBug() {
        // The shape of ConversationViewModel before the fix.
        val buggy = """
            class Vm(val c: Container) : ViewModel() {
                val me = c.me
                init {
                    viewModelScope.launch { maxFile.value = c.messages.maxAttachmentBytes() }
                    if (inCall) println("note {")
                    println("${'$'}{label.length}")
                }
                private val inCall get() = c.calls.active
                // maxFile { is declared below
                val maxFile = MutableStateFlow(1L)
                val note by lazy { "x" }
                val label = "y"
            }
        """.trimIndent()
        // A string template is code, so `label` counts; `inCall` has no backing field, and "note" is only text.
        assertEquals(
            listOf("class Vm: init uses maxFile (declared after it)", "class Vm: init uses label (declared after it)"),
            InitOrder.violations(buggy),
        )
        val fixed = buggy.replace("val maxFile = MutableStateFlow(1L)\n", "").replace("val label = \"y\"\n", "")
            .replace("val me = c.me", "val me = c.me\n    val maxFile = MutableStateFlow(1L)\n    val label = \"y\"")
        assertEquals(emptyList<String>(), InitOrder.violations(fixed))
    }

    @Test
    fun delegatesAndNestedClassesAreChecked() {
        val code = """
            object Holder {
                init { start(cache) }
                val cache by lazy { mutableMapOf<String, Int>() }
                class Inner {
                    val a = 1
                    init { use(a) }
                }
            }
        """.trimIndent()
        assertEquals(listOf("object Holder: init uses cache (declared after it)"), InitOrder.violations(code))
    }
}

/** A small Kotlin source scanner: comments and string contents removed, then class bodies matched by braces. */
object InitOrder {
    private val decl = Regex("""^\s*(?:@\w+(?:\([^)]*\))?\s+)*(?:(?:private|internal|protected|public|override|open|final|lateinit|const)\s+)*(val|var)\s+(\w+)""")
    private val initStart = Regex("""^\s*init\s*\{""")
    private val classStart = Regex("""\b(class|object)\s+(\w+)[^{;=]*\{""")

    fun violations(source: String): List<String> {
        val code = strip(source)
        val out = mutableListOf<String>()
        for (m in classStart.findAll(code)) {
            val open = m.range.last
            val close = matching(code, open) ?: continue
            val body = code.substring(open + 1, close)
            val members = topLevelLines(body)
            val props = mutableMapOf<String, Int>() // name -> line index
            val inits = mutableListOf<Pair<Int, String>>()
            members.forEachIndexed { i, (line, block) ->
                decl.find(line)?.let { d ->
                    val rest = line.substring(d.range.last + 1)
                    // `val x get() = …` and `val x: T get() = …` have no backing field.
                    val getterOnly = Regex("""^\s*(?::[^=]*)?\bget\s*\(""").containsMatchIn(rest)
                    if (!getterOnly) props.putIfAbsent(d.groupValues[2], i)
                }
                if (initStart.containsMatchIn(line)) inits += i to block
            }
            for ((at, block) in inits) {
                for ((name, declaredAt) in props) {
                    if (declaredAt > at && Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(block)) {
                        out += "${m.groupValues[1]} ${m.groupValues[2]}: init uses $name (declared after it)"
                    }
                }
            }
        }
        return out
    }

    /** The class body's own members: each top-level line, with the full text of any block it opens. */
    private fun topLevelLines(body: String): List<Pair<String, String>> {
        val res = mutableListOf<Pair<String, String>>()
        var depth = 0
        var i = 0
        var lineStart = 0
        while (i <= body.length) {
            val ch = if (i < body.length) body[i] else '\n'
            if (depth == 0 && ch == '\n') {
                val line = body.substring(lineStart, minOf(i, body.length))
                if (line.isNotBlank()) {
                    val brace = line.indexOf('{')
                    val block = if (brace >= 0) {
                        val close = matching(body, lineStart + brace)
                        body.substring(lineStart + brace, (close ?: body.length - 1) + 1)
                    } else line
                    res += line to block
                }
                lineStart = i + 1
            }
            if (ch == '{') depth++
            if (ch == '}') depth--
            i++
        }
        return res
    }

    private fun matching(s: String, open: Int): Int? {
        var d = 0
        for (i in open until s.length) {
            when (s[i]) {
                '{' -> d++
                '}' -> { d--; if (d == 0) return i }
            }
        }
        return null
    }

    /**
     * Removes comments and the text of string and char literals, keeping line breaks (so lines still line up) and the
     * code inside string templates (`$name`, `${expr}`), which is a real use of a property.
     */
    fun strip(s: String): String {
        val b = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("//", i) -> { while (i < s.length && s[i] != '\n') i++ }
                s.startsWith("/*", i) -> {
                    val end = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                    s.substring(i, end).forEach { if (it == '\n') b.append('\n') }
                    i = end
                }
                s.startsWith("\"\"\"", i) -> i = string(s, i + 3, raw = true, b)
                c == '"' -> i = string(s, i + 1, raw = false, b)
                c == '\'' && i + 2 < s.length && (s[i + 2] == '\'' || s[i + 1] == '\\') -> {
                    val end = s.indexOf('\'', i + 2).let { if (it < 0) s.length else it + 1 }
                    b.append("' '")
                    i = end
                }
                else -> { b.append(c); i++ }
            }
        }
        return b.toString()
    }

    /** Copies a string literal starting after its opening quote as `"" <template code>`; returns the index after it. */
    private fun string(s: String, from: Int, raw: Boolean, b: StringBuilder): Int {
        b.append("\"\"")
        var i = from
        while (i < s.length) {
            if (raw && s.startsWith("\"\"\"", i)) return i + 3
            val c = s[i]
            if (!raw && (c == '"' || c == '\n')) return i + 1
            when {
                !raw && c == '\\' -> i += 2
                c == '\n' -> { b.append('\n'); i++ }
                c == '$' && i + 1 < s.length && s[i + 1] == '{' -> {
                    var d = 0
                    var j = i + 1
                    while (j < s.length) {
                        if (s[j] == '{') d++ else if (s[j] == '}') { d--; if (d == 0) break }
                        j++
                    }
                    b.append(' ').append(strip(s.substring(i + 2, minOf(j, s.length)))).append(' ')
                    i = j + 1
                }
                c == '$' && i + 1 < s.length && (s[i + 1].isLetter() || s[i + 1] == '_') -> {
                    var j = i + 1
                    while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                    b.append(' ').append(s, i + 1, j).append(' ')
                    i = j
                }
                else -> i++
            }
        }
        return i
    }
}
