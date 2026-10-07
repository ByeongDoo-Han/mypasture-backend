package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.application.ScriptureCopyCommand
import com.example.mypasture.bible.application.ScriptureCopyService
import com.example.mypasture.bible.domain.ScriptureCopyEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 로그인한 사용자의 필사 기록을 HTTP 요청·응답으로 변환합니다. */
@RestController
@RequestMapping("/api/v1/scripture-copies")
class ScriptureCopyController(private val service: ScriptureCopyService) : ScriptureCopyApi {
    override fun save(principal: JwtPrincipal, id: UUID, request: ScriptureCopyRequest) = service.save(
        principal.userId, id, ScriptureCopyCommand(request.version, request.bookCode, request.chapter, request.verse,
            request.typedText, request.reflection, request.expectedRevision, request.complete, request.endVerse),
    ).toResponse()

    override fun updateReflection(principal: JwtPrincipal, id: UUID, request: ScriptureCopyReflectionRequest) =
        service.updateReflection(principal.userId, id, request.reflection, request.expectedRevision).toResponse()

    override fun list(principal: JwtPrincipal, page: Int, size: Int): ScriptureCopyPageResponse {
        val records = service.list(principal.userId, page, size)
        return ScriptureCopyPageResponse(records.content.map { it.toResponse() }, records.number, records.size, records.totalElements, records.totalPages)
    }

    override fun get(principal: JwtPrincipal, id: UUID) = service.get(principal.userId, id).toResponse()

    override fun delete(principal: JwtPrincipal, id: UUID, expectedRevision: Long) = service.delete(principal.userId, id, expectedRevision)
}

private fun ScriptureCopyEntity.toResponse() = ScriptureCopyResponse(
    id, version, bookCode, bookName, chapter, verse, endVerse, sourceText, typedText, reflection, revision, status, createdAt, updatedAt, completedAt,
)
