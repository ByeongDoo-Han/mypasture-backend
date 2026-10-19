package com.example.mypasture.reading.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant
import java.time.LocalDate

data class RecordReadingRequest(@field:Size(max = 40) val version: String, @field:Size(max = 40) val bookCode: String, @field:Min(1) val chapter: Int, @field:Min(1) @field:Max(200) val lastVerse: Int, val completed: Boolean = false, @field:Size(max = 80) val timezone: String = "Asia/Seoul")
data class ReadingStateResponse(val version: String, val bookCode: String, val bookName: String, val chapter: Int, val lastVerse: Int, val completed: Boolean, val lastReadAt: Instant)
data class ReadingHistoryResponse(val version: String, val bookCode: String, val bookName: String, val chapter: Int, val readOn: LocalDate, val lastVerse: Int, val completed: Boolean)

/** 사용자별 성경 읽기 진행률과 날짜별 기록 API 계약입니다. */
@Tag(name = "성경 읽기", description = "본인의 읽기 진도와 기록을 관리합니다.")
interface ReadingApi {
    /** 장의 실제 절 범위를 벗어나거나 timezone이 잘못되면 INVALID_REQUEST(400), 저장은 롤백됩니다. */
    @Operation(summary = "성경 읽기 진도 저장", description = "읽은 절 범위와 시간대를 검증해 본인 진도를 저장합니다. 잘못된 범위나 시간대는 400이며 저장하지 않습니다.")
    @PutMapping("/progress") fun record(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: RecordReadingRequest): ReadingStateResponse
    @Operation(summary = "내 성경 읽기 진도", description = "로그인한 사용자의 현재 읽기 진도를 조회합니다.")
    @GetMapping("/progress") fun progress(@AuthenticationPrincipal principal: JwtPrincipal): List<ReadingStateResponse>
    /** from <= to인 최대 366일(양끝 포함) 범위만 허용하며 위반 시 INVALID_REQUEST(400)입니다. */
    @Operation(summary = "내 성경 읽기 기록", description = "from부터 to까지 양끝 포함 최대 366일의 본인 읽기 기록을 조회합니다. 범위가 잘못되면 400을 반환합니다.")
    @GetMapping("/history") fun history(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회 범위 시작 날짜(YYYY-MM-DD)(포함).") @RequestParam from: LocalDate, @Parameter(description = "조회 범위 종료 날짜(YYYY-MM-DD)(포함).") @RequestParam to: LocalDate): List<ReadingHistoryResponse>
}
