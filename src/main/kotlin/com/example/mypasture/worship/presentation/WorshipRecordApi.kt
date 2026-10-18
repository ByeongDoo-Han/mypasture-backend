package com.example.mypasture.worship.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.worship.domain.WorshipServiceType
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.security.SecurityRequirement
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 작성 화면에서 제출한 공통 예배 질문과 말씀 범위입니다. */
data class WorshipRecordRequest(
    val worshipDate: LocalDate,
    val serviceType: WorshipServiceType,
    @field:Size(max = 120) val title: String? = null,
    @field:NotBlank @field:Size(max = 40) val bibleVersion: String,
    @field:NotBlank @field:Size(max = 40) val bookCode: String,
    @field:Positive val chapter: Int,
    @field:Positive val startVerse: Int,
    @field:Positive val endVerse: Int,
    @field:NotBlank @field:Size(max = 2000) val heard: String,
    @field:Size(max = 2000) val lifeConnection: String? = null,
    @field:Size(max = 2000) val personalResponse: String? = null,
    @field:Size(max = 2000) val prayer: String? = null,
)

/** 낙관적 잠금 버전을 포함한 수정 요청입니다. */
data class UpdateWorshipRecordRequest(
    @field:PositiveOrZero val expectedRevision: Long,
    @field:Valid val record: WorshipRecordRequest,
)

/** 소유 사용자에게 반환하는 예배 기록입니다. */
data class WorshipRecordResponse(
    val id: UUID,
    val revision: Long,
    val worshipDate: LocalDate,
    val serviceType: WorshipServiceType,
    val title: String?,
    val bibleVersion: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val startVerse: Int,
    val endVerse: Int,
    val heard: String,
    val lifeConnection: String?,
    val personalResponse: String?,
    val prayer: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** 예배 기록 페이지의 항목과 페이지 정보를 반환합니다. */
data class WorshipRecordPageResponse(
    val items: List<WorshipRecordResponse>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

/** 인증된 사용자의 비공개 예배 기록 API 계약입니다. */
@Tag(name = "예배 기록", description = "내 예배 기록을 말씀 범위와 함께 비공개로 관리합니다.")
@SecurityRequirement(name = "bearerAuth")
interface WorshipRecordApi {
    @GetMapping
    @Operation(summary = "내 예배 기록 목록", description = "로그인한 사용자의 기록만 페이지로 반환합니다.")
    fun list(@AuthenticationPrincipal principal: JwtPrincipal, @RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): WorshipRecordPageResponse

    @GetMapping("/{id}")
    @Operation(summary = "내 예배 기록 조회")
    fun get(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable id: UUID): WorshipRecordResponse

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "예배 기록 작성", description = "같은 번역본·권·장의 연속된 1~50절만 저장할 수 있습니다.")
    fun create(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: WorshipRecordRequest): WorshipRecordResponse

    @PutMapping("/{id}")
    @Operation(summary = "내 예배 기록 수정", description = "expectedRevision이 현재 revision과 다르면 409를 반환합니다.")
    fun update(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable id: UUID, @Valid @RequestBody request: UpdateWorshipRecordRequest): WorshipRecordResponse

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "내 예배 기록 삭제")
    fun delete(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable id: UUID)
}
