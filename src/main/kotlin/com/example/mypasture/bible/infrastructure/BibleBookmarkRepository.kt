package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleBookmarkEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** 사용자별 말씀 저장의 잠금, 중복 확인, 최신순 조회와 삭제를 담당합니다. */
interface BibleBookmarkRepository : JpaRepository<BibleBookmarkEntity, UUID> {
    /** 같은 사용자의 동시 저장 요청을 DB 행 잠금으로 직렬화합니다. */
    @Query(value = "select 1 from users where id = :userId for update", nativeQuery = true)
    fun lockUser(userId: UUID): Int?

    fun findByUserIdAndVerse_Id(userId: UUID, verseId: Long): BibleBookmarkEntity?

    @Query(
        value = """
            select bookmark from BibleBookmarkEntity bookmark
            join fetch bookmark.verse verse
            join fetch verse.book
            where bookmark.userId = :userId
            order by bookmark.createdAt desc, bookmark.id desc
        """,
        countQuery = "select count(bookmark) from BibleBookmarkEntity bookmark where bookmark.userId = :userId",
    )
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<BibleBookmarkEntity>

    @Query(
        """
            select bookmark.verse.verse from BibleBookmarkEntity bookmark
            where bookmark.userId = :userId
              and bookmark.verse.version = :version
              and bookmark.verse.book.id = :bookId
              and bookmark.verse.chapter = :chapter
            order by bookmark.verse.verse
        """,
    )
    fun findVerseNumbers(userId: UUID, version: String, bookId: Long, chapter: Int): List<Int>

    fun deleteByUserIdAndVerse_Id(userId: UUID, verseId: Long): Long
}
