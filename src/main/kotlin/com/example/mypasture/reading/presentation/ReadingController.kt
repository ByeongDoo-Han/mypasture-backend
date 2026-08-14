package com.example.mypasture.reading.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.reading.application.ReadingService
import com.example.mypasture.reading.domain.BibleReadingHistoryEntity
import com.example.mypasture.reading.domain.BibleReadingStateEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate

/** 인증된 사용자의 성경 읽기 위치와 이력을 HTTP API로 제공합니다. */
@RestController
@RequestMapping("/api/v1/reading")
class ReadingController(private val service: ReadingService) : ReadingApi {
    override fun record(principal: JwtPrincipal, request: RecordReadingRequest) = service.record(principal.userId, request.version, request.bookCode, request.chapter, request.lastVerse, request.completed, request.timezone).toResponse()
    override fun progress(principal: JwtPrincipal) = service.states(principal.userId).map { it.toResponse() }
    override fun history(principal: JwtPrincipal, from: LocalDate, to: LocalDate) = service.history(principal.userId, from, to).map { it.toResponse() }
}

private fun BibleReadingStateEntity.toResponse() = ReadingStateResponse(bibleVersion, bookCode, chapter, lastVerse, completed, lastReadAt)
private fun BibleReadingHistoryEntity.toResponse() = ReadingHistoryResponse(bibleVersion, bookCode, chapter, readOn, lastVerse, completed)
