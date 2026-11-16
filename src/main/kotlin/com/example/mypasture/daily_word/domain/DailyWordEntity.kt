package com.example.mypasture.daily_word.domain

import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 오늘의 말씀 콘텐츠가 사용자에게 공개되기 전 거치는 검수 상태입니다. */
enum class DailyWordStatus { DRAFT, PUBLISHED, REJECTED }

/** 오늘의 말씀 본문과 묵상이 만들어진 경로를 구분합니다. */
enum class DailyWordSource { AI, MANUAL, CURATED }

/** 비동기 생성 작업의 대기, 처리, 완료와 최종 실패 상태입니다. */
enum class DailyWordGenerationJobStatus { PENDING, PROCESSING, COMPLETED, FAILED, CANCELLED }

/** 모든 사용자에게 같은 날 제공되는 검증 가능한 오늘의 말씀과 생성·검수 이력입니다. */
@Entity
@Table(name = "daily_words")
class DailyWordEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(unique = true) val wordDate: LocalDate,
    var verseId: Long? = null,
    var bibleVersion: String,
    var bookCode: String,
    var bookName: String,
    var chapter: Int,
    var verse: Int,
    @Column(columnDefinition = "text") var verseText: String,
    @Column(columnDefinition = "text") var meditation: String,
    @Column(columnDefinition = "text") var actionQuestion: String,
    /** V25 이전 애플리케이션과 롤백 호환성을 위해 유지하며 status와 함께 갱신합니다. */
    var published: Boolean = false,
    @Enumerated(EnumType.STRING) var status: DailyWordStatus = DailyWordStatus.DRAFT,
    @Enumerated(EnumType.STRING) var source: DailyWordSource = DailyWordSource.AI,
    var contentRevision: Int = 1,
    var model: String? = null,
    var promptVersion: String? = null,
    @Enumerated(EnumType.STRING) var retrievalStrategy: RagRetrievalStrategy? = null,
    var embeddingTokens: Int = 0,
    var promptTokens: Int = 0,
    var completionTokens: Int = 0,
    @Column(precision = 18, scale = 8) var estimatedCostUsd: BigDecimal = BigDecimal.ZERO,
    var generatedAt: Instant? = null,
    var reviewedBy: UUID? = null,
    var reviewedAt: Instant? = null,
    var publishedAt: Instant? = null,
    @Column(length = 500) var rejectionReason: String? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
    @Version var entityVersion: Long = 0,
    var endVerse: Int? = null,
    @Column(columnDefinition = "text") var passageText: String? = null,
    @Column(length = 200) var topicTags: String = "",
    @Enumerated(EnumType.STRING) var readingMode: DailyWordReadingMode? = null,
    @Enumerated(EnumType.STRING) var passageType: DailyWordPassageType? = null,
    @Column(length = 100) var rotationEntryId: String? = null,
) {
    val topics: List<String> get() = topicTags.split(',').filter { it.isNotBlank() }

    /** 단일 절 수동/AI 편집이 이전 순환 범위 메타데이터를 남기지 않게 합니다. */
    private fun clearRotation() {
        endVerse = null
        passageText = null
        topicTags = ""
        readingMode = null
        passageType = null
        rotationEntryId = null
    }
    /** RAG가 반환한 실제 절과 생성 결과로 기존 초안을 새 revision으로 교체합니다. */
    fun replaceDraft(citation: BibleCitation, canonicalBookCode: String, generated: GeneratedDailyWord, now: Instant) {
        clearRotation()
        verseId = citation.verseId
        bibleVersion = citation.version
        bookCode = canonicalBookCode
        bookName = generated.bookName
        chapter = citation.chapter
        verse = citation.verse
        verseText = citation.text
        meditation = generated.meditation.trim()
        actionQuestion = generated.actionQuestion.trim()
        published = false
        status = DailyWordStatus.DRAFT
        source = generated.source
        contentRevision += 1
        model = generated.model
        promptVersion = generated.promptVersion
        retrievalStrategy = generated.retrievalStrategy
        embeddingTokens = generated.embeddingTokens
        promptTokens = generated.promptTokens
        completionTokens = generated.completionTokens
        estimatedCostUsd = generated.estimatedCostUsd
        generatedAt = now
        reviewedBy = null
        reviewedAt = null
        publishedAt = null
        rejectionReason = null
        updatedAt = now
    }

    /** 관리자가 실제 절을 다시 지정하고 사용자 노출 문구를 수정합니다. */
    fun edit(citation: BibleCitation, canonicalBookCode: String, bookDisplayName: String, nextMeditation: String, nextQuestion: String, now: Instant) {
        check(status == DailyWordStatus.DRAFT) { "초안 상태의 오늘의 말씀만 수정할 수 있습니다." }
        clearRotation()
        verseId = citation.verseId
        bibleVersion = citation.version
        bookCode = canonicalBookCode
        bookName = bookDisplayName
        chapter = citation.chapter
        verse = citation.verse
        verseText = citation.text
        meditation = nextMeditation.trim()
        actionQuestion = nextQuestion.trim()
        source = DailyWordSource.MANUAL
        contentRevision += 1
        updatedAt = now
    }

    /** 장애 복구용 수동 초안으로 교체하고 이전 AI 비용·모델 메타데이터를 제거합니다. */
    fun replaceManualDraft(
        citation: BibleCitation,
        canonicalBookCode: String,
        bookDisplayName: String,
        nextMeditation: String,
        nextQuestion: String,
        now: Instant,
    ) {
        check(status != DailyWordStatus.PUBLISHED) { "게시된 오늘의 말씀은 수동 초안으로 교체할 수 없습니다." }
        clearRotation()
        verseId = citation.verseId
        bibleVersion = citation.version
        bookCode = canonicalBookCode
        bookName = bookDisplayName
        chapter = citation.chapter
        verse = citation.verse
        verseText = citation.text
        meditation = nextMeditation.trim()
        actionQuestion = nextQuestion.trim()
        published = false
        status = DailyWordStatus.DRAFT
        source = DailyWordSource.MANUAL
        contentRevision += 1
        model = null
        promptVersion = null
        retrievalStrategy = null
        embeddingTokens = 0
        promptTokens = 0
        completionTokens = 0
        estimatedCostUsd = BigDecimal.ZERO
        generatedAt = now
        reviewedBy = null
        reviewedAt = null
        publishedAt = null
        rejectionReason = null
        updatedAt = now
    }

    /** 검증된 초안만 게시하고 기존 published 플래그도 함께 유지합니다. */
    fun publish(adminId: UUID, now: Instant) {
        check(status == DailyWordStatus.DRAFT) { "초안 상태의 오늘의 말씀만 게시할 수 있습니다." }
        check(verseId != null) { "실제 성경 절과 연결되지 않은 콘텐츠는 게시할 수 없습니다." }
        status = DailyWordStatus.PUBLISHED
        published = true
        reviewedBy = adminId
        reviewedAt = now
        publishedAt = now
        rejectionReason = null
        updatedAt = now
    }

    /** 검수에서 반려한 이유를 남기고 사용자 노출을 차단합니다. */
    fun reject(adminId: UUID, reason: String, now: Instant) {
        check(status == DailyWordStatus.DRAFT) { "초안 상태의 오늘의 말씀만 반려할 수 있습니다." }
        status = DailyWordStatus.REJECTED
        published = false
        reviewedBy = adminId
        reviewedAt = now
        rejectionReason = reason.take(500)
        updatedAt = now
    }
}

/** AI 또는 로컬 provider가 실제 절을 바꾸지 않고 생성한 묵상 초안입니다. */
data class GeneratedDailyWord(
    val bookName: String,
    val meditation: String,
    val actionQuestion: String,
    val source: DailyWordSource,
    val model: String,
    val promptVersion: String,
    val retrievalStrategy: RagRetrievalStrategy,
    val embeddingTokens: Int,
    val promptTokens: Int,
    val completionTokens: Int,
    val estimatedCostUsd: BigDecimal,
)

/** 워커가 lease token으로 소유권을 증명하며 처리하는 날짜별 생성 작업입니다. */
@Entity
@Table(name = "daily_word_generation_jobs")
class DailyWordGenerationJobEntity(
    @Id val id: UUID = UUID.randomUUID(),
    val wordDate: LocalDate,
    @Enumerated(EnumType.STRING) var status: DailyWordGenerationJobStatus = DailyWordGenerationJobStatus.PENDING,
    var attemptCount: Int = 0,
    val requestedBy: UUID? = null,
    @Column(length = 500) val requestReason: String? = null,
    var leaseToken: UUID? = null,
    var leaseUntil: Instant? = null,
    @Column(length = 1000) var errorMessage: String? = null,
    var dailyWordId: UUID? = null,
    val createdAt: Instant = Instant.now(),
    var updatedAt: Instant = Instant.now(),
) {
    fun claim(token: UUID, until: Instant, now: Instant) {
        status = DailyWordGenerationJobStatus.PROCESSING
        attemptCount += 1
        leaseToken = token
        leaseUntil = until
        errorMessage = null
        updatedAt = now
    }

    fun complete(wordId: UUID, token: UUID, now: Instant) {
        check(status == DailyWordGenerationJobStatus.PROCESSING && leaseToken == token) { "생성 작업 lease가 유효하지 않습니다." }
        status = DailyWordGenerationJobStatus.COMPLETED
        dailyWordId = wordId
        leaseToken = null
        leaseUntil = null
        updatedAt = now
    }

    fun fail(token: UUID, message: String, retry: Boolean, now: Instant) {
        if (status != DailyWordGenerationJobStatus.PROCESSING || leaseToken != token) return
        status = if (retry) DailyWordGenerationJobStatus.PENDING else DailyWordGenerationJobStatus.FAILED
        errorMessage = message.take(1000)
        leaseToken = null
        leaseUntil = null
        updatedAt = now
    }

    /** 수동 복구가 시작되면 아직 실행 중인 AI 작업을 명시적으로 종료합니다. */
    fun cancel(message: String, now: Instant) {
        check(status == DailyWordGenerationJobStatus.PENDING || status == DailyWordGenerationJobStatus.PROCESSING) {
            "활성 생성 작업만 취소할 수 있습니다."
        }
        status = DailyWordGenerationJobStatus.CANCELLED
        errorMessage = message.take(1_000)
        leaseToken = null
        leaseUntil = null
        updatedAt = now
    }
}
