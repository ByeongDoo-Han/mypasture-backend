package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDate

/** 본인의 연도별 잔디와 오늘 방문 API 계약입니다. 날짜와 사용자 ID는 서버가 결정합니다. */
interface ActivityCalendarApi {
    @GetMapping
    fun calendar(@AuthenticationPrincipal principal: JwtPrincipal, @RequestParam(required = false) year: Int?): ActivityCalendarResponse

    @PutMapping("/today")
    fun visit(@AuthenticationPrincipal principal: JwtPrincipal): VisitResponse
}

/** 한국 시간 기준 연도별 활동 API 응답입니다. */
data class ActivityCalendarResponse(val year: Int, val timezone: String, val today: LocalDate, val days: List<ActivityDayResponse>)
/** 해당 날짜의 방문 여부와 중복 제거한 읽기 장 수입니다. */
data class ActivityDayResponse(val date: LocalDate, val visited: Boolean, val chaptersRead: Int, val chaptersCompleted: Int)
/** 중복 호출에도 동일한 당일 방문 결과를 반환합니다. */
data class VisitResponse(val date: LocalDate)
