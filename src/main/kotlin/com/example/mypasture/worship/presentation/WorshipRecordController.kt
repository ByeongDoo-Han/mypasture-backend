package com.example.mypasture.worship.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.worship.application.WorshipRecordInput
import com.example.mypasture.worship.application.WorshipRecordService
import com.example.mypasture.worship.domain.WorshipRecordEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 비공개 예배 기록 요청을 사용자별 저장과 조회에 연결합니다. */
@RestController
@RequestMapping("/api/v1/worship-records")
class WorshipRecordController(private val service: WorshipRecordService) : WorshipRecordApi {
    override fun list(principal: JwtPrincipal, page: Int, size: Int): WorshipRecordPageResponse {
        val records = service.list(principal.userId, page, size)
        return WorshipRecordPageResponse(records.content.map { it.toResponse() }, records.number, records.size, records.totalElements, records.totalPages)
    }

    override fun get(principal: JwtPrincipal, id: UUID) = service.get(principal.userId, id).toResponse()
    override fun create(principal: JwtPrincipal, request: WorshipRecordRequest) = service.create(principal.userId, request.toInput()).toResponse()
    override fun update(principal: JwtPrincipal, id: UUID, request: UpdateWorshipRecordRequest) =
        service.update(principal.userId, id, request.expectedRevision, request.record.toInput()).toResponse()

    override fun delete(principal: JwtPrincipal, id: UUID) = service.delete(principal.userId, id)
}

private fun WorshipRecordRequest.toInput() = WorshipRecordInput(
    worshipDate, serviceType, title, bibleVersion, bookCode, chapter, startVerse, endVerse,
    heard, lifeConnection, personalResponse, prayer,
)

private fun WorshipRecordEntity.toResponse() = WorshipRecordResponse(
    id, revision, worshipDate, serviceType, title, bibleVersion, bookCode, bookName, chapter,
    startVerse, endVerse, heard, lifeConnection, personalResponse, prayer, createdAt, updatedAt,
)
