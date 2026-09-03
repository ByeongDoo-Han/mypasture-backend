package com.example.mypasture.bible.domain

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToMany
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 생성된 장별 해설이 검수와 게시 과정 중 어느 단계에 있는지 표현합니다. */
enum class BibleCommentaryStatus { DRAFT, PUBLISHED, REJECTED, SUPERSEDED }

/** AI 자동 평가와 사람 검수 중 현재 해설이 거친 검증 수준입니다. */
enum class BibleCommentaryEvaluationStatus { NOT_EVALUATED, PASSED, FAILED, MANUAL_REVIEW }

/** 장별 AI 해설 생성 작업의 실행 상태를 표현합니다. */
enum class BibleCommentaryJobStatus { PENDING, PROCESSING, COMPLETED, FAILED }

/** 해설에 반복해서 포함되는 주제, 묵상 질문, 해석상 주의사항을 구분합니다. */
enum class BibleCommentaryItemType { KEY_THEME, REFLECTION_QUESTION, CAUTION }

/** 해설 생성 모델에 제공할 승인된 배경 메모의 성격입니다. */
enum class BibleCommentaryEvidenceType { HISTORICAL, LITERARY, THEOLOGICAL }

/** 배경 메모가 생성 입력에 포함될 수 있는지 나타냅니다. */
enum class BibleCommentaryEvidenceStatus { DRAFT, APPROVED, RETIRED }

/** 해설에 수행된 사람 검수 행위를 감사 가능한 형태로 구분합니다. */
enum class BibleCommentaryReviewAction { EDITED, PUBLISHED, REJECTED }

/** 한 장의 검수 가능한 AI 해설과 생성 이력, 게시 상태를 보관하는 JPA entity입니다. */
@Entity
@Table(name = "bible_chapter_commentaries")
class BibleChapterCommentaryEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    val generationJobId: UUID? = null,
    val version: String,
    val bookCode: String,
    val chapter: Int,
    val revision: Int,
    @Enumerated(EnumType.STRING)
    var status: BibleCommentaryStatus = BibleCommentaryStatus.DRAFT,
    @Column(columnDefinition = "text")
    var summary: String,
    @Column(columnDefinition = "text")
    var historicalContext: String,
    val sourceHash: String,
    val promptVersion: String,
    val model: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
    var qualityScore: Int? = null,
    @Enumerated(EnumType.STRING)
    var evaluationStatus: BibleCommentaryEvaluationStatus = BibleCommentaryEvaluationStatus.NOT_EVALUATED,
    var evaluatorModel: String? = null,
    var evaluationScore: Int? = null,
    var evaluationGroundingScore: Int? = null,
    var evaluationHistoricalEvidenceScore: Int? = null,
    var evaluationReferenceRelevanceScore: Int? = null,
    var evaluationTheologicalNeutralityScore: Int? = null,
    var evaluationClarityScore: Int? = null,
    var fabricatedScripture: Boolean = false,
    var unsupportedHistoricalClaims: Boolean = false,
    @Column(columnDefinition = "text")
    var evaluationIssues: String? = null,
    var evaluationPromptTokens: Int = 0,
    var evaluationCompletionTokens: Int = 0,
    var evaluationCostUsd: BigDecimal = BigDecimal.ZERO,
    @Column(columnDefinition = "text")
    var reviewNotes: String? = null,
    var lastEditedBy: UUID? = null,
    var lastEditedAt: Instant? = null,
    var reviewedBy: UUID? = null,
    var reviewedAt: Instant? = null,
    var publishedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    @OneToMany(mappedBy = "commentary", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val sections: MutableList<BibleCommentarySectionEntity> = mutableListOf()

    @OneToMany(mappedBy = "commentary", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val items: MutableList<BibleCommentaryItemEntity> = mutableListOf()

    @OneToMany(mappedBy = "commentary", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    val references: MutableList<BibleCommentaryReferenceEntity> = mutableListOf()

    /** 검수자가 현재 초안을 게시 상태로 전환합니다. */
    fun publish(reviewerId: UUID, score: Int, notes: String, now: Instant) {
        status = BibleCommentaryStatus.PUBLISHED
        qualityScore = score
        reviewNotes = notes
        reviewedBy = reviewerId
        reviewedAt = now
        publishedAt = now
        updatedAt = now
    }

    /** 새 버전 게시로 더 이상 노출되지 않는 이전 해설을 보존합니다. */
    fun supersede(now: Instant) {
        status = BibleCommentaryStatus.SUPERSEDED
        updatedAt = now
    }

    /** 검수 기준을 충족하지 못한 초안을 재사용하지 않도록 거절 처리합니다. */
    fun reject(reviewerId: UUID, notes: String, now: Instant) {
        status = BibleCommentaryStatus.REJECTED
        reviewNotes = notes
        reviewedBy = reviewerId
        reviewedAt = now
        updatedAt = now
    }
}

/** 저작권과 출처가 확인된 배경 메모를 초안 생성에만 제공하는 JPA entity입니다. */
@Entity
@Table(name = "bible_commentary_evidence_notes")
class BibleCommentaryEvidenceEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    val version: String,
    val bookCode: String,
    val chapter: Int? = null,
    @Enumerated(EnumType.STRING)
    val evidenceType: BibleCommentaryEvidenceType,
    var title: String,
    @Column(columnDefinition = "text")
    var content: String,
    var sourceName: String,
    var sourceUrl: String? = null,
    var licenseNote: String,
    @Enumerated(EnumType.STRING)
    var status: BibleCommentaryEvidenceStatus = BibleCommentaryEvidenceStatus.DRAFT,
    val createdBy: UUID,
    var approvedBy: UUID? = null,
    var approvedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    /** 관리자 승인을 받은 메모만 AI 생성 근거로 사용할 수 있게 전환합니다. */
    fun approve(adminId: UUID, now: Instant) {
        check(status == BibleCommentaryEvidenceStatus.DRAFT) { "초안 근거만 승인할 수 있습니다." }
        status = BibleCommentaryEvidenceStatus.APPROVED
        approvedBy = adminId
        approvedAt = now
        updatedAt = now
    }

    /** 더 이상 신뢰할 수 없거나 사용권이 만료된 근거를 생성 입력에서 제외합니다. */
    fun retire(now: Instant) {
        check(status != BibleCommentaryEvidenceStatus.RETIRED) { "이미 폐기된 근거입니다." }
        status = BibleCommentaryEvidenceStatus.RETIRED
        updatedAt = now
    }
}

/** 해설 수정·게시·거절의 담당자와 사유를 보존하는 감사 JPA entity입니다. */
@Entity
@Table(name = "bible_commentary_review_events")
class BibleCommentaryReviewEventEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    val commentaryId: UUID,
    val previousCommentaryId: UUID? = null,
    @Enumerated(EnumType.STRING)
    val action: BibleCommentaryReviewAction,
    val reviewerId: UUID,
    @Column(columnDefinition = "text")
    val notes: String,
    val createdAt: Instant = Instant.now(),
)

/** 한 장 안에서 연속된 절 범위를 설명하는 해설 구간 entity입니다. */
@Entity
@Table(name = "bible_commentary_sections")
class BibleCommentarySectionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commentary_id")
    val commentary: BibleChapterCommentaryEntity,
    val sectionOrder: Int,
    val startVerse: Int,
    val endVerse: Int,
    val title: String,
    @Column(columnDefinition = "text")
    val explanation: String,
)

/** 해설의 핵심 주제, 묵상 질문, 주의사항을 순서대로 보관하는 entity입니다. */
@Entity
@Table(name = "bible_commentary_items")
class BibleCommentaryItemEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commentary_id")
    val commentary: BibleChapterCommentaryEntity,
    @Enumerated(EnumType.STRING)
    val itemType: BibleCommentaryItemType,
    val itemOrder: Int,
    val content: String,
)

/** 해설이 참조하는 실제 성경 위치만 저장해 화면에서 원문을 DB로 조회할 수 있게 합니다. */
@Entity
@Table(name = "bible_commentary_references")
class BibleCommentaryReferenceEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "commentary_id")
    val commentary: BibleChapterCommentaryEntity,
    val referenceOrder: Int,
    val bookCode: String,
    val chapter: Int,
    val verse: Int,
    val reason: String,
)

/** 비동기 AI 생성 요청과 재시도 정보를 보관하는 JPA entity입니다. */
@Entity
@Table(name = "bible_commentary_generation_jobs")
class BibleCommentaryGenerationJobEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    val version: String,
    val bookCode: String,
    val chapter: Int,
    @Enumerated(EnumType.STRING)
    var status: BibleCommentaryJobStatus = BibleCommentaryJobStatus.PENDING,
    var attemptCount: Int = 0,
    val requestedBy: UUID? = null,
    var lockedAt: Instant? = null,
    var errorMessage: String? = null,
    var commentaryId: UUID? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    /** 작업을 한 워커가 소유하도록 표시해 여러 서버의 중복 생성을 막습니다. */
    fun start(now: Instant) {
        status = BibleCommentaryJobStatus.PROCESSING
        attemptCount += 1
        lockedAt = now
        errorMessage = null
        updatedAt = now
    }

    /** 생성과 자동 검증을 통과한 해설 식별자를 작업에 연결합니다. */
    fun complete(generatedCommentaryId: UUID, now: Instant) {
        status = BibleCommentaryJobStatus.COMPLETED
        commentaryId = generatedCommentaryId
        lockedAt = null
        updatedAt = now
    }

    /** 실패 원인을 관리자가 확인할 수 있도록 저장하고 작업을 종료합니다. */
    fun fail(message: String, now: Instant) {
        status = BibleCommentaryJobStatus.FAILED
        errorMessage = message.take(1000)
        lockedAt = null
        updatedAt = now
    }
}
