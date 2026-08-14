package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.application.BibleContentRepository
import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleVerse
import com.example.mypasture.config.BibleProperties
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import org.springframework.stereotype.Repository

/** `data/kor_html`의 1910 성경 HTML 파일을 장 단위로 읽고 파싱하는 repository adapter입니다. */
@Repository
class Kor1910HtmlBibleContentRepository(
    private val bibleProperties: BibleProperties,
) : BibleContentRepository {
    private val cache = ConcurrentHashMap<ChapterCacheKey, BibleChapter>()
    private val verseRegex = Regex(
        """<span\s+class=["']verse["']\s+id=["']V(\d+)["']>.*?</span>(.*?)(?=</div>)""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tagRegex = Regex("""<[^>]+>""")
    private val whitespaceRegex = Regex("""\s+""")

    override fun getChapter(version: String, book: BibleBook, chapter: Int): BibleChapter? {
        if (!version.equals(BibleCatalog.KOR1910_VERSION, ignoreCase = true)) {
            return null
        }

        return cache.getOrPut(ChapterCacheKey(book.code, chapter)) {
            parseChapter(book, chapter) ?: return null
        }
    }

    override fun searchVerses(version: String, keyword: String): List<BibleVerse> {
        if (!version.equals(BibleCatalog.KOR1910_VERSION, ignoreCase = true)) {
            return emptyList()
        }

        val matches = mutableListOf<BibleVerse>()
        BibleCatalog.books.forEach { book ->
            (1..book.chapterCount).forEach { chapter ->
                getChapter(version, book, chapter)
                    ?.verses
                    ?.filterTo(matches) { it.text.contains(keyword, ignoreCase = true) }
            }
        }
        return matches
    }

    private fun parseChapter(book: BibleBook, chapter: Int): BibleChapter? {
        val file = Path.of(bibleProperties.kor1910HtmlPath, "${book.code}${chapter.toString().padStart(2, '0')}.htm")
        if (!Files.exists(file)) {
            return null
        }

        val html = Files.readString(file, StandardCharsets.UTF_8).removePrefix("\uFEFF")
        val verses =
            verseRegex.findAll(html)
                .map { match ->
                    val verseNumber = match.groupValues[1].toInt()
                    BibleVerse(
                        version = BibleCatalog.KOR1910_VERSION,
                        bookCode = book.code,
                        bookName = book.name,
                        chapter = chapter,
                        verse = verseNumber,
                        text = cleanVerseText(match.groupValues[2]),
                    )
                }
                .filter { it.text.isNotBlank() }
                .toList()

        if (verses.isEmpty()) {
            return null
        }

        return BibleChapter(
            version = BibleCatalog.KOR1910_VERSION,
            book = book,
            chapter = chapter,
            previous = BibleCatalog.previousOf(book, chapter),
            next = BibleCatalog.nextOf(book, chapter),
            verses = verses,
        )
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
            .replace(Regex("""&#(\d+);""")) { match ->
                match.groupValues[1].toInt().toChar().toString()
            }
}

/** 파싱한 장 본문을 메모리에 캐싱하기 위한 key입니다. */
private data class ChapterCacheKey(
    val bookCode: String,
    val chapter: Int,
)
