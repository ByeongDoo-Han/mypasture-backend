package com.example.mypasture.quiet_time.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 공용 QT 질문의 생성 상태와 비용을 저장하며 DB 스키마 검증에도 사용됩니다. */
@Entity
@Table(name = "quiet_time_shared_question_sets", uniqueConstraints = [UniqueConstraint(
    name = "uk_quiet_time_shared_question_key",
    columnNames = ["passage_id", "focus_theme_code", "question_style", "generator_version", "source_hash"],
)])
class QuietTimeSharedQuestionEntity(
    @Id val id: UUID,
    val passageId: UUID,
    @Column(length = 50) val focusThemeCode: String,
    @Column(length = 30) val questionStyle: String,
    val generatorVersion: Int,
    @Column(length = 64) val sourceHash: String,
    val createdByUserId: UUID,
    @Column(length = 20) val status: String,
    val claimToken: UUID,
    val leaseExpiresAt: Instant,
    @Column(columnDefinition = "text") val questionSetJson: String?,
    @Column(length = 120) val model: String?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    @Column(precision = 18, scale = 9) val estimatedCostUsd: BigDecimal?,
    val createdAt: Instant,
    val completedAt: Instant?,
)
