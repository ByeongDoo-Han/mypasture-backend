package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleNoteEntity
import com.example.mypasture.bible.domain.BibleNoteVerseEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

/** 사용자 소유 메모의 페이지 조회와 단건 소유권 조회를 담당합니다. */
interface BibleNoteRepository : JpaRepository<BibleNoteEntity, UUID> {
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<BibleNoteEntity>

    fun findByIdAndUserId(id: UUID, userId: UUID): BibleNoteEntity?
}

/** 메모와 성경 절의 연결을 선택 순서대로 조회하고 교체합니다. */
interface BibleNoteVerseRepository : JpaRepository<BibleNoteVerseEntity, UUID> {
    @Query(
        """
        select link from BibleNoteVerseEntity link
        join fetch link.note note
        join fetch link.verse verse
        join fetch verse.book
        where note.id in :noteIds
        order by link.sortOrder
        """,
    )
    fun findAllByNoteIds(noteIds: Collection<UUID>): List<BibleNoteVerseEntity>

    @Modifying
    @Query("delete from BibleNoteVerseEntity link where link.note.id = :noteId")
    fun deleteAllByNoteId(noteId: UUID)
}
