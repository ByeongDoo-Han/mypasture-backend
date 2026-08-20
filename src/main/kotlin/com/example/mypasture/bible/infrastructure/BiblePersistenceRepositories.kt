package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleBookEntity
import com.example.mypasture.bible.domain.BibleChapterEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

/** 성경 권 메타데이터의 버전별 upsert와 조회를 담당합니다. */
interface BibleBookEntityRepository : JpaRepository<BibleBookEntity, Long> {
    fun findByVersionAndCode(version: String, code: String): BibleBookEntity?
    fun findAllByVersionOrderByDisplayOrder(version: String): List<BibleBookEntity>
}

/** 장별 원문 적재 상태를 조회하고 저장합니다. */
interface BibleChapterEntityRepository : JpaRepository<BibleChapterEntity, Long> {
    fun findByVersionAndBook_IdAndChapter(version: String, bookId: Long, chapter: Int): BibleChapterEntity?
    fun findAllByVersion(version: String): List<BibleChapterEntity>
    fun countByVersionAndContentStatus(version: String, contentStatus: com.example.mypasture.bible.domain.BibleChapterContentStatus): Long
}

/** 절 ID를 유지하면서 본문을 저장하고 장 조회 및 단어 검색을 제공합니다. */
interface BibleVerseEntityRepository : JpaRepository<BibleVerseEntity, Long> {
    fun findAllByVersionAndBook_IdAndChapterOrderByVerse(
        version: String,
        bookId: Long,
        chapter: Int,
    ): List<BibleVerseEntity>

    fun findByVersionAndBook_IdAndChapterAndVerse(
        version: String,
        bookId: Long,
        chapter: Int,
        verse: Int,
    ): BibleVerseEntity?

    @Query(
        value = """
            select v from BibleVerseEntity v join fetch v.book b
            where v.version = :version and lower(v.text) like lower(concat('%', :keyword, '%'))
            order by b.displayOrder, v.chapter, v.verse
        """,
        countQuery = """
            select count(v) from BibleVerseEntity v
            where v.version = :version and lower(v.text) like lower(concat('%', :keyword, '%'))
        """,
    )
    fun search(version: String, keyword: String, pageable: Pageable): Page<BibleVerseEntity>

    @Query(
        value = """
            select v from BibleVerseEntity v join fetch v.book b
            where v.version = :version and b.id = :bookId
              and lower(v.text) like lower(concat('%', :keyword, '%'))
            order by v.chapter, v.verse
        """,
        countQuery = """
            select count(v) from BibleVerseEntity v
            where v.version = :version and v.book.id = :bookId
              and lower(v.text) like lower(concat('%', :keyword, '%'))
        """,
    )
    fun searchInBook(version: String, bookId: Long, keyword: String, pageable: Pageable): Page<BibleVerseEntity>
}
