package com.example.mypasture.notification.presentation

import com.example.mypasture.notification.application.NotificationOperations
import com.example.mypasture.notification.domain.NotificationType
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.Instant

/** 관리자 알림 운영 현황을 개인정보 없이 조회하는 OpenAPI 계약입니다. */
@RequestMapping("/api/v1/admin/notifications")
interface NotificationAdminApi {
    @GetMapping("/operations")
    fun operations(
        @RequestParam type: NotificationType?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): NotificationOperations
}
