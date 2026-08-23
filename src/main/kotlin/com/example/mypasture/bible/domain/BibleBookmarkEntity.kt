package com.example.mypasture.bible.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** 사용자가 저장한 성경 한 절을 사용자와 안정적인 절 ID에 연결합니다. */
@Entity
@Table(
    name = "bookmarks",
    uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "verse_id"])],
)
class BibleBookmarkEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "verse_id", nullable = false)
    val verse: BibleVerseEntity,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
)
