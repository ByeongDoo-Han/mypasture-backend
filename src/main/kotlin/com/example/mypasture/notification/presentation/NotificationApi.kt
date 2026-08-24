package com.example.mypasture.notification.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.application.NotificationInboxItem
import com.example.mypasture.notification.application.NotificationInboxPage
import com.example.mypasture.notification.application.NotificationUnreadCount
import com.example.mypasture.notification.application.NotificationDeliveryEvent
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.time.LocalTime
import java.util.UUID

data class RegisterDeviceRequest(@field:Size(min = 8, max = 120) val deviceId: String, @field:Size(min = 20, max = 500) val fcmToken: String, val platform: DevicePlatform)
data class DailyWordNotificationSettingRequest(val enabled: Boolean, val notificationTime: LocalTime, @field:Size(max = 80) val timezone: String)
data class DailyWordNotificationSettingResponse(val enabled: Boolean, val notificationTime: LocalTime, val timezone: String)
data class NotificationDeliveryAcknowledgementRequest(
    @field:Size(min = 8, max = 120) val deviceId: String,
    val event: NotificationDeliveryEvent,
)

/** FCM 기기와 오늘의 말씀 알림 설정 API 계약입니다. */
interface NotificationApi {
    @PostMapping("/devices") @ResponseStatus(HttpStatus.NO_CONTENT) fun register(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: RegisterDeviceRequest)
    @DeleteMapping("/devices/{deviceId}") @ResponseStatus(HttpStatus.NO_CONTENT) fun unregister(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable deviceId: String)
    @GetMapping("/settings/daily-word") fun setting(@AuthenticationPrincipal principal: JwtPrincipal): DailyWordNotificationSettingResponse
    @PutMapping("/settings/daily-word") fun update(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: DailyWordNotificationSettingRequest): DailyWordNotificationSettingResponse
    @GetMapping fun inbox(@AuthenticationPrincipal principal: JwtPrincipal, @RequestParam cursor: String?, @RequestParam(defaultValue = "20") limit: Int): NotificationInboxPage
    @GetMapping("/unread-count") fun unreadCount(@AuthenticationPrincipal principal: JwtPrincipal): NotificationUnreadCount
    @PostMapping("/{id}/read") fun markRead(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable id: UUID): NotificationInboxItem
    @PostMapping("/read-all") fun markAllRead(@AuthenticationPrincipal principal: JwtPrincipal): NotificationUnreadCount
    @PostMapping("/deliveries/{deliveryId}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun acknowledgeDelivery(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @PathVariable deliveryId: UUID,
        @Valid @RequestBody request: NotificationDeliveryAcknowledgementRequest,
    )
}
