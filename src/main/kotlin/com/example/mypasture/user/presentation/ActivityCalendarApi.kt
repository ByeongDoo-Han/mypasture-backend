package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

/** 본인의 연도별 잔디와 오늘 방문 API 계약입니다. 날짜와 사용자 ID는 서버가 결정합니다. */
@Tag(name = "활동 달력", description = "본인의 방문 기록과 연도별 활동 달력을 조회합니다.")
interface ActivityCalendarApi {
    @Operation(summary = "내 활동 달력 조회", description = "로그인한 사용자의 지정 연도 활동 달력을 반환합니다. 연도를 생략하면 현재 연도를 사용합니다.")
    @GetMapping
    fun calendar(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "활동 달력을 조회할 연도. 생략하면 현재 연도입니다.") @RequestParam(required = false) year: Int?): ActivityCalendarResponse

    @Operation(summary = "오늘 방문 기록", description = "서버 기준 오늘의 방문을 본인 활동 달력에 멱등하게 기록합니다.")
    @PutMapping("/today")
    fun visit(@AuthenticationPrincipal principal: JwtPrincipal): VisitResponse
}

/** 한국 시간 기준 연도별 활동 API 응답입니다. */
data class ActivityCalendarResponse(val year: Int, val timezone: String, val today: LocalDate, val days: List<ActivityDayResponse>)
/** 해당 날짜의 방문 여부와 중복 제거한 읽기 장 수입니다. */
data class ActivityDayResponse(val date: LocalDate, val visited: Boolean, val chaptersRead: Int, val chaptersCompleted: Int)
/** 중복 호출에도 동일한 당일 방문 결과를 반환합니다. */
data class VisitResponse(val date: LocalDate)
