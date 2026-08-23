package com.example.mypasture.ai.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** 사용 목적별 활성 버전과 다음 버전 번호를 잠그는 작은 포인터 entity입니다. */
@Entity
@Table(name = "ai_runtime_configs")
class AiRuntimeConfigPointerEntity(
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "use_case", length = 40)
    val useCase: AiUseCase,
    @Column(name = "active_version_id")
    var activeVersionId: UUID? = null,
    @Column(name = "version_counter", nullable = false)
    var versionCounter: Long = 0,
) {
    fun nextVersion(): Long = ++versionCounter
    fun activate(versionId: UUID) { activeVersionId = versionId }
}

/** 관리자 변경마다 새로 생성되어 과거 설정을 수정 없이 보존하는 AI 설정 버전입니다. */
@Entity
@Table(
    name = "ai_runtime_config_versions",
    uniqueConstraints = [UniqueConstraint(name = "uk_ai_config_use_case_version", columnNames = ["use_case", "version_number"])],
)
class AiRuntimeConfigVersionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Enumerated(EnumType.STRING)
    @Column(name = "use_case", nullable = false, length = 40)
    val useCase: AiUseCase,
    @Column(name = "version_number", nullable = false)
    val versionNumber: Long,
    @Column(name = "generator_model", nullable = false, length = 80)
    val generatorModel: String,
    @Column(name = "evaluator_model", nullable = false, length = 80)
    val evaluatorModel: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "generator_reasoning_effort", nullable = false, length = 20)
    val generatorReasoningEffort: AiReasoningEffort,
    @Enumerated(EnumType.STRING)
    @Column(name = "evaluator_reasoning_effort", nullable = false, length = 20)
    val evaluatorReasoningEffort: AiReasoningEffort,
    @Column(name = "max_output_tokens", nullable = false)
    val maxOutputTokens: Int,
    @Column(name = "evaluation_max_output_tokens", nullable = false)
    val evaluationMaxOutputTokens: Int,
    @Column(name = "evaluation_enabled", nullable = false)
    val evaluationEnabled: Boolean,
    @Column(name = "evaluation_threshold", nullable = false)
    val evaluationThreshold: Int,
    @Column(name = "max_revision_count", nullable = false)
    val maxRevisionCount: Int,
    @Column(name = "previous_version_id")
    val previousVersionId: UUID?,
    @Column(name = "created_by")
    val createdBy: UUID?,
    @Column(nullable = false, length = 500)
    val reason: String,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    fun snapshot(source: String = "DATABASE") = AiRuntimeConfigSnapshot(
        id = id,
        useCase = useCase,
        version = versionNumber,
        generatorModel = generatorModel,
        evaluatorModel = evaluatorModel,
        generatorReasoningEffort = generatorReasoningEffort,
        evaluatorReasoningEffort = evaluatorReasoningEffort,
        maxOutputTokens = maxOutputTokens,
        evaluationMaxOutputTokens = evaluationMaxOutputTokens,
        evaluationEnabled = evaluationEnabled,
        evaluationThreshold = evaluationThreshold,
        maxRevisionCount = maxRevisionCount,
        source = source,
    )
}
