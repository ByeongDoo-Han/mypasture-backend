package com.example.mypasture.notification.presentation

import com.example.mypasture.notification.application.NotificationOperations
import com.example.mypasture.notification.domain.NotificationType
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant

/** 관리자 알림 운영 현황을 개인정보 없이 조회하는 OpenAPI 계약입니다. */
@RequestMapping("/api/v1/admin/notifications")
@Tag(name = "관리자 · 알림", description = "알림 발송 운영 지표를 조회합니다.")
interface NotificationAdminApi {
    @Operation(summary = "알림 발송 운영 현황", description = "관리자가 알림 유형과 기간으로 발송 운영 현황을 조회합니다. 개인정보가 담긴 개별 내용은 반환하지 않습니다.")
    @GetMapping("/operations")
    fun operations(
        @Parameter(description = "집계할 알림 종류.") @RequestParam type: NotificationType?,
        @Parameter(description = "조회 범위 시작 시각(ISO-8601)(포함).") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @Parameter(description = "조회 범위 종료 시각(ISO-8601)(포함).") @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @Parameter(description = "0부터 시작하는 페이지 번호.") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") size: Int,
    ): NotificationOperations
}
