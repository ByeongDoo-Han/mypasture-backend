package com.example.mypasture.user.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.user.application.ActivityCalendarService
import org.springframework.http.ResponseEntity
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.CommonErrorCode
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.*

/** 사용자 신원은 JWT에서만 얻고 집계 결과를 HTTP 응답으로 변환합니다. */
@RestController
@RequestMapping("/api/v1/activity")
class ActivityCalendarController(private val service: ActivityCalendarService) : ActivityCalendarApi {
    override fun calendar(principal: JwtPrincipal, year: Int?): ActivityCalendarResponse {
        val result = service.calendar(principal.userId, year)
        return ActivityCalendarResponse(result.year, result.timezone, result.today, result.days.map {
            ActivityDayResponse(it.date, it.visited, it.chaptersRead, it.chaptersCompleted)
        })
    }
    @ExceptionHandler(IllegalArgumentException::class)
    fun invalidYear(request: HttpServletRequest): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(ApiErrorResponse.of(CommonErrorCode.INVALID_REQUEST, request))

    override fun visit(principal: JwtPrincipal) = VisitResponse(service.visit(principal.userId))
}
