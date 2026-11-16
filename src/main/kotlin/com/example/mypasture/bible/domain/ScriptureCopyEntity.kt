package com.example.mypasture.bible.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** 사용자가 옮겨 적은 말씀의 원문 스냅샷과 수정 이력을 보존합니다. */
@Entity
@Table(name = "scripture_copies")
class ScriptureCopyEntity(
    @Id val id: UUID,
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(nullable = false, length = 40) val version: String,
    @Column(name = "book_code", nullable = false, length = 40) val bookCode: String,
    @Column(name = "book_name", nullable = false, length = 100) val bookName: String,
    @Column(nullable = false) val chapter: Int,
    @Column(nullable = false) val verse: Int,
    @Column(name = "end_verse", nullable = false) val endVerse: Int = verse,
    @Column(name = "source_text", nullable = false, columnDefinition = "text") val sourceText: String,
    @Column(name = "typed_text", nullable = false, columnDefinition = "text") var typedText: String,
    @Column(nullable = false, columnDefinition = "text") var reflection: String,
    @Column(nullable = false) var revision: Long = 1,
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) var status: ScriptureCopyStatus = ScriptureCopyStatus.DRAFT,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = createdAt,
    @Column(name = "completed_at") var completedAt: Instant? = null,
)

/** 필사 기록의 작성 중·완료 상태를 구분합니다. */
enum class ScriptureCopyStatus { DRAFT, COMPLETED }
