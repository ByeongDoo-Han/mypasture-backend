package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.config.BibleProperties
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import org.springframework.stereotype.Component

/** 1910 성경 HTML 파일의 이름 차이를 흡수하고 장별 절 원문을 검증해 파싱합니다. */
@Component
class Kor1910HtmlBibleParser(
    private val bibleProperties: BibleProperties,
) {
    private val root: Path get() = Path.of(bibleProperties.kor1910HtmlPath)
    private val verseRegex = Regex(
        """<span\s+class=["']verse["']\s+id=["']V(\d+)["']>.*?</span>(.*?)(?=</div>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    fun validateSourceDirectory() {
        require(Files.isDirectory(root)) { "Bible source directory does not exist: $root" }
        val containsHtml = Files.list(root).use { files ->
            files.anyMatch { Files.isRegularFile(it) && it.fileName.toString().endsWith(".htm", ignoreCase = true) }
        }
        require(containsHtml) { "Bible source directory contains no HTML files: $root" }
    }

    fun readChapter(book: BibleBook, chapter: Int): ParsedBibleChapterSource? {
        val file = resolveChapterFile(book, chapter) ?: return null
        val bytes = Files.readAllBytes(file)
        val html = bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val verses = verseRegex.findAll(html)
            .map { match -> ParsedBibleVerse(match.groupValues[1].toInt(), cleanVerseText(match.groupValues[2])) }
            .filter { it.text.isNotBlank() }
            .toList()

        require(verses.isNotEmpty()) { "Bible chapter contains no verses: $file" }
        require(verses.all { it.verse > 0 }) { "Bible chapter contains an invalid verse number: $file" }
        require(verses.map { it.verse }.distinct().size == verses.size) { "Bible chapter contains duplicate verses: $file" }

        return ParsedBibleChapterSource(
            sourcePath = file.fileName.toString(),
            checksum = MessageDigest.getInstance("SHA-256").digest(bytes).toHex(),
            verses = verses,
        )
    }

    private fun resolveChapterFile(book: BibleBook, chapter: Int): Path? {
        val candidates = listOf(
            root.resolve("${book.code}${chapter.toString().padStart(2, '0')}.htm"),
            root.resolve("${book.code}${chapter.toString().padStart(3, '0')}.htm"),
        ).distinct()
        return candidates.firstOrNull(Files::isRegularFile)
    }

    private fun cleanVerseText(rawHtml: String): String =
        rawHtml
            .replace(tagRegex, " ")
            .decodeHtmlEntities()
            .replace(whitespaceRegex, " ")
            .trim()

    private fun String.decodeHtmlEntities(): String =
        this
            .replace("&#160;", " ")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace(Regex("""&#(\d+);""")) { match -> match.groupValues[1].toInt().toChar().toString() }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
}

/** 한 장의 원본 파일 식별 정보와 파싱된 절 목록입니다. */
data class ParsedBibleChapterSource(
    val sourcePath: String,
    val checksum: String,
    val verses: List<ParsedBibleVerse>,
)

/** importer가 DB 절 행으로 변환할 검증된 절 번호와 본문입니다. */
data class ParsedBibleVerse(val verse: Int, val text: String)
