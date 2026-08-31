package com.example.mypasture.bible.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import jakarta.persistence.Version
import java.time.Instant
import java.util.UUID

/** 사용자가 하나 이상의 말씀에 남긴 개인 메모와 수정 충돌 감지용 revision을 저장합니다. */
@Entity
@Table(name = "bible_notes")
class BibleNoteEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(columnDefinition = "text", nullable = false)
    var content: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
    @Version
    @Column(nullable = false)
    var revision: Long = 0,
) {
    fun revise(nextContent: String, now: Instant = Instant.now()) {
        content = nextContent
        updatedAt = now
    }
}

/** 한 메모에 포함된 성경 절과 사용자가 선택한 순서를 연결합니다. */
@Entity
@Table(
    name = "bible_note_verses",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["note_id", "verse_id"]),
        UniqueConstraint(columnNames = ["note_id", "sort_order"]),
    ],
)
class BibleNoteVerseEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "note_id", nullable = false)
    val note: BibleNoteEntity,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verse_id", nullable = false)
    val verse: BibleVerseEntity,
    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int,
)
