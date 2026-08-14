package com.example.mypasture.attendance.presentation

import com.example.mypasture.attendance.application.AttendanceService
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

data class AttendanceItemResponse(val id: UUID, val pastureId: UUID, val sessionId: UUID, val date: LocalDate)
data class AttendanceSummaryResponse(val currentStreak: Int, val total: Int, val attendances: List<AttendanceItemResponse>)

/** 현재 사용자의 출석 기록과 연속 출석을 조회합니다. */
@RestController @RequestMapping("/api/v1/attendance")
class AttendanceController(private val service: AttendanceService) {
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: JwtPrincipal, @RequestParam(defaultValue = "2000-01-01") from: LocalDate, @RequestParam(defaultValue = "2100-01-01") to: LocalDate): AttendanceSummaryResponse {
        val result = service.summary(principal.userId, from, to)
        return AttendanceSummaryResponse(result.currentStreak, result.records.size, result.records.map { AttendanceItemResponse(it.id, it.pastureId, it.sessionId, it.attendanceDate) })
    }
}
