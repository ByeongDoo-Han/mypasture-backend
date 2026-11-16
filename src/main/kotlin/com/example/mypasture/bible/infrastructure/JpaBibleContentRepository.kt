package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.application.BibleContentRepository
import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapter
import com.example.mypasture.bible.domain.BibleSearchResult
import com.example.mypasture.bible.domain.BibleVerse
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.domain.BibleSearchHit
import com.example.mypasture.bible.domain.BibleSearchPosition
import jakarta.persistence.EntityManager
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

/** DB에 적재된 절을 장 단위로 조합하고 페이지 기반 본문 검색을 수행합니다. */
@Repository
class JpaBibleContentRepository(
    private val bookRepository: BibleBookEntityRepository,
    private val chapterRepository: BibleChapterEntityRepository,
    private val verseRepository: BibleVerseEntityRepository,
    private val entityManager: EntityManager,
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

    @Transactional(readOnly = true)
    override fun searchVersesAfter(version: String, keyword: String, after: BibleSearchPosition?, limit: Int): List<BibleSearchHit> {
        val positionPredicate = if (after == null) "" else """
            and (b.displayOrder > :bookOrder
              or (b.displayOrder = :bookOrder and v.chapter > :chapter)
              or (b.displayOrder = :bookOrder and v.chapter = :chapter and v.verse > :verse)
              or (b.displayOrder = :bookOrder and v.chapter = :chapter and v.verse = :verse and v.id > :id))
        """
        val query = entityManager.createQuery("""
            select v from BibleVerseEntity v join fetch v.book b
            where v.version = :version and lower(v.text) like :pattern escape '!'
            $positionPredicate
            order by b.displayOrder, v.chapter, v.verse, v.id
        """.trimIndent(), BibleVerseEntity::class.java)
            .setParameter("version", version)
            .setParameter("pattern", "%${literalPattern(keyword.lowercase(Locale.ROOT))}%")
            .setMaxResults(limit)
        if (after != null) query.setParameter("bookOrder", after.bookOrder).setParameter("chapter", after.chapter)
            .setParameter("verse", after.verse).setParameter("id", after.id)
        return query.resultList.map { entity ->
            BibleSearchHit(
                BibleVerse(version, entity.book.code, entity.book.name, entity.chapter, entity.verse, entity.text),
                BibleSearchPosition(entity.book.displayOrder, entity.chapter, entity.verse, entity.id),
            )
        }
    }

    private fun literalPattern(keyword: String) = keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_")
}
