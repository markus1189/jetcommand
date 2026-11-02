package link.thesymbolic.jetcommand.protocol

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `core/` imports neither coroutines, `java.util.concurrent`, `ports` nor `shell`, and declares no
 * `suspend` function; `ports/` doesn't import `shell`.
 */
class ArchitectureTest {
    private val root = File("src/main/kotlin/link/thesymbolic/jetcommand/protocol")
    private val pkg = "link.thesymbolic.jetcommand.protocol"

    private fun violations(dir: String, forbidden: List<Regex>): List<String> {
        val files = File(root, dir).walk().filter { it.extension == "kt" }.toList()
        assertTrue(files.isNotEmpty(), "no sources under ${File(root, dir).absolutePath}")
        return files.flatMap { f ->
            f.readLines()
                .withIndex()
                .filter { (_, l) -> forbidden.any { it.containsMatchIn(l) } }
                .map { (i, l) -> "$dir/${f.name}:${i + 1}: ${l.trim()}" }
        }
    }

    @Test
    fun `core stays pure`() {
        val forbidden =
            listOf(
                Regex("""^import kotlinx\.coroutines\."""),
                Regex("""^import java\.util\.concurrent\."""),
                Regex("""^import ${Regex.escape(pkg)}\.(shell|ports)\."""),
                Regex("""\bsuspend\s+fun\b"""),
            )
        assertEquals(emptyList(), violations("core", forbidden))
    }

    @Test
    fun `ports don't know the shell`() {
        val forbidden = listOf(Regex("""^import ${Regex.escape(pkg)}\.shell\."""))
        assertEquals(emptyList(), violations("ports", forbidden))
    }
}
