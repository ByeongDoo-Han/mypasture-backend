package com.example.mypasture.daily_word.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobEntity
import com.example.mypasture.daily_word.domain.DailyWordStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 관리자 오늘의 말씀 HTTP 요청을 생성·검수 application service에 전달합니다. */
@RestController
@RequestMapping("/api/v1/admin/daily-words")
class DailyWordAdminController(private val service: DailyWordService) : DailyWordAdminApi {
    override fun list(from: LocalDate?, to: LocalDate?, status: DailyWordStatus?): List<DailyWordAdminResponse> {
        val end = to ?: LocalDate.now(SEOUL).plusDays(14)
        val start = from ?: end.minusDays(30)
        return service.list(start, end, status).map { it.toAdminResponse() }
    }

    override fun jobs() = service.listJobs().map { it.toResponse() }

    override suspend fun generate(principal: JwtPrincipal, date: LocalDate, request: GenerateDailyWordRequest) =
        ResponseEntity.accepted().body(
            service.queueGeneration(date, principal.userId, request.operationId, request.regenerate, request.reason).toResponse(),
        )

    override fun update(principal: JwtPrincipal, id: UUID, request: UpdateDailyWordRequest) =
        service.updateDraft(id, principal.userId, request.operationId, request.verseId, request.meditation, request.actionQuestion, request.reason).toAdminResponse()

    override fun publish(principal: JwtPrincipal, id: UUID, request: ReviewDailyWordRequest) =
        service.publish(id, principal.userId, request.operationId, request.reason).toAdminResponse()

    override fun reject(principal: JwtPrincipal, id: UUID, request: ReviewDailyWordRequest) =
        service.reject(id, principal.userId, request.operationId, request.reason).toAdminResponse()

    companion object { private val SEOUL = ZoneId.of("Asia/Seoul") }
}

private fun DailyWordEntity.toAdminResponse() = DailyWordAdminResponse(
    id, wordDate, verseId, bibleVersion, bookCode, bookName, chapter, verse, verseText,
    meditation, actionQuestion, status, source, contentRevision, model, promptVersion,
    retrievalStrategy?.name, embeddingTokens, promptTokens, completionTokens, estimatedCostUsd,
    reviewedBy, rejectionReason, generatedAt, reviewedAt, publishedAt, updatedAt,
)

private fun DailyWordGenerationJobEntity.toResponse() = DailyWordGenerationJobResponse(
    id, wordDate, status, attemptCount, errorMessage, dailyWordId, createdAt, updatedAt,
)
