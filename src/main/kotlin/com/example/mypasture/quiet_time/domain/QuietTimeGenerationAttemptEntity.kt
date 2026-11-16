package com.example.mypasture.quiet_time.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** AI 질문 생성 시도의 일일 한도를 영속적으로 기록합니다. */
@Entity
@Table(name = "quiet_time_question_generation_attempts")
class QuietTimeGenerationAttemptEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "created_at", nullable = false) val createdAt: Instant,
)
