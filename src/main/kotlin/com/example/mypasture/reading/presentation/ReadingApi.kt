package com.example.mypasture.reading.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
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
interface ReadingApi {
    /** 장의 실제 절 범위를 벗어나거나 timezone이 잘못되면 INVALID_REQUEST(400), 저장은 롤백됩니다. */
    @PutMapping("/progress") fun record(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: RecordReadingRequest): ReadingStateResponse
    @GetMapping("/progress") fun progress(@AuthenticationPrincipal principal: JwtPrincipal): List<ReadingStateResponse>
    /** from <= to인 최대 366일(양끝 포함) 범위만 허용하며 위반 시 INVALID_REQUEST(400)입니다. */
    @GetMapping("/history") fun history(@AuthenticationPrincipal principal: JwtPrincipal, @RequestParam from: LocalDate, @RequestParam to: LocalDate): List<ReadingHistoryResponse>
}
