package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleSearchResult
import com.example.mypasture.bible.domain.BibleVerse
import com.example.mypasture.bible.domain.BibleVersion
import org.springframework.stereotype.Service

/** 성경 본문 저장소가 제공해야 하는 읽기/검색 동작을 정의하는 application port입니다. */
interface BibleContentRepository {
    fun getChapter(version: String, book: BibleBook, chapter: Int): BibleChapter?

    fun searchVerses(version: String, keyword: String, page: Int, size: Int): BibleSearchResult
}

/** 성경 번역본, 권, 장, 절 조회 규칙을 조합하는 application service입니다. */
@Service
class BibleReaderService(
    private val bibleContentRepository: BibleContentRepository,
) {
    fun getVersions(): List<BibleVersion> = BibleCatalog.versions

    fun getBooks(version: String): List<BibleBook> {
        requireSupportedVersion(version)
        return BibleCatalog.books
    }

    fun getChapter(
        version: String,
        bookCodeOrName: String,
        chapter: Int,
    ): BibleChapter {
        requireSupportedVersion(version)
        val book = requireBook(bookCodeOrName)
        requireChapter(book, chapter)
        return bibleContentRepository.getChapter(version.uppercase(), book, chapter)
            ?: throw BibleNotFoundException("Bible chapter was not imported: ${book.code} $chapter")
    }

    fun getVerse(
        version: String,
        bookCodeOrName: String,
        chapter: Int,
        verse: Int,
    ): BibleVerse {
        require(verse > 0) { "Verse must be greater than 0." }
        return getChapter(version, bookCodeOrName, chapter).verses.firstOrNull { it.verse == verse }
            ?: throw BibleNotFoundException("Bible verse was not found: $bookCodeOrName $chapter:$verse")
    }

    fun search(
        version: String,
        keyword: String,
        page: Int,
        size: Int,
    ): BibleSearchResult {
        requireSupportedVersion(version)
        val normalizedKeyword = keyword.trim()
        require(normalizedKeyword.length in 1..50) { "Keyword length must be between 1 and 50." }
        require(page >= 0) { "Page must be greater than or equal to 0." }
        require(size in 1..100) { "Size must be between 1 and 100." }

        return bibleContentRepository.searchVerses(version.uppercase(), normalizedKeyword, page, size)
    }

    private fun requireSupportedVersion(version: String) {
        if (BibleCatalog.getVersion(version) == null) {
            throw BibleNotFoundException("Unsupported Bible version: $version")
        }
    }

    private fun requireBook(bookCodeOrName: String): BibleBook =
        BibleCatalog.findBook(bookCodeOrName)
            ?: throw BibleNotFoundException("Bible book was not found: $bookCodeOrName")

    private fun requireChapter(book: BibleBook, chapter: Int) {
        if (chapter !in 1..book.chapterCount) {
            throw BibleNotFoundException("Bible chapter was not found: ${book.code} $chapter")
        }
    }
}

/** 요청한 번역본, 권, 장, 절을 찾을 수 없을 때 사용하는 application 예외입니다. */
class BibleNotFoundException(message: String) : RuntimeException(message)
