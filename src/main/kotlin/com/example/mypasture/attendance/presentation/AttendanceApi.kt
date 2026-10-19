package com.example.mypasture.attendance.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import java.time.LocalDate
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam

/** 본인의 QT 출석 기록과 연속 출석 조회 계약입니다. */
@Tag(name = "QT 출석", description = "로그인한 사용자의 QT 출석 기록과 연속 출석을 조회합니다.")
interface AttendanceApi {
    @Operation(summary = "내 QT 출석 조회", description = "지정한 날짜 범위의 본인 출석 목록, 총 출석 수와 현재 연속 출석 횟수를 반환합니다.")
    @GetMapping("/me")
    fun me(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회 시작 날짜(YYYY-MM-DD, 포함).") @RequestParam(defaultValue = "2000-01-01") from: LocalDate,
        @Parameter(description = "조회 종료 날짜(YYYY-MM-DD, 포함).") @RequestParam(defaultValue = "2100-01-01") to: LocalDate,
    ): AttendanceSummaryResponse
}
