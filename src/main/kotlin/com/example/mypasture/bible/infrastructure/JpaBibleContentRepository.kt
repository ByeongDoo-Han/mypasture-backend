package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.application.BibleContentRepository
import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleSearchResult
import com.example.mypasture.bible.domain.BibleVerse
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository

/** DB에 적재된 절을 장 단위로 조합하고 페이지 기반 본문 검색을 수행합니다. */
@Repository
class JpaBibleContentRepository(
    private val bookRepository: BibleBookEntityRepository,
    private val chapterRepository: BibleChapterEntityRepository,
    private val verseRepository: BibleVerseEntityRepository,
) : BibleContentRepository {
    override fun getChapter(version: String, book: BibleBook, chapter: Int): BibleChapter? {
        val storedBook = bookRepository.findByVersionAndCode(version, book.code) ?: return null
        val storedChapter = chapterRepository.findByVersionAndBook_IdAndChapter(version, storedBook.id, chapter) ?: return null
        val verses = verseRepository.findAllByVersionAndBook_IdAndChapterOrderByVerse(version, storedBook.id, chapter)
            .map { entity ->
                BibleVerse(version, book.code, book.name, chapter, entity.verse, entity.text)
            }
        return BibleChapter(
            version = version,
            book = book,
            chapter = chapter,
            contentStatus = storedChapter.contentStatus,
            previous = BibleCatalog.previousOf(book, chapter),
            next = BibleCatalog.nextOf(book, chapter),
            verses = verses,
        )
    }

    override fun searchVerses(version: String, keyword: String, page: Int, size: Int): BibleSearchResult {
        val result = verseRepository.search(version, keyword, PageRequest.of(page, size))
        val verses = result.content.map { entity ->
            BibleVerse(version, entity.book.code, entity.book.name, entity.chapter, entity.verse, entity.text)
        }
        return BibleSearchResult(
            version = version,
            keyword = keyword,
            page = page,
            size = size,
            total = result.totalElements.toInt(),
            hasNext = result.hasNext(),
            verses = verses,
        )
    }
}
