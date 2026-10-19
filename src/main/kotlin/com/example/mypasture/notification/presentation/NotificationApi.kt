package com.example.mypasture.notification.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.notification.domain.DevicePlatform
import com.example.mypasture.notification.application.NotificationInboxItem
import com.example.mypasture.notification.application.NotificationInboxPage
import com.example.mypasture.notification.application.NotificationUnreadCount
import com.example.mypasture.notification.application.NotificationDeliveryEvent
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
@Tag(name = "알림", description = "기기 등록, 알림 설정과 내 알림함을 관리합니다.")
interface NotificationApi {
    @Operation(summary = "알림 기기 등록", description = "로그인한 사용자의 기기 푸시 토큰을 등록하거나 갱신합니다.")
    @PostMapping("/devices") @ResponseStatus(HttpStatus.NO_CONTENT) fun register(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: RegisterDeviceRequest)
    @Operation(summary = "알림 기기 해제", description = "내 계정에 등록된 기기를 알림 대상에서 제거합니다.")
    @DeleteMapping("/devices/{deviceId}") @ResponseStatus(HttpStatus.NO_CONTENT) fun unregister(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "해제할 본인 기기 식별자.") @PathVariable deviceId: String)
    @Operation(summary = "오늘의 말씀 알림 설정 조회", description = "로그인한 사용자의 오늘의 말씀 알림 설정을 조회합니다.")
    @GetMapping("/settings/daily-word") fun setting(@AuthenticationPrincipal principal: JwtPrincipal): DailyWordNotificationSettingResponse
    @Operation(summary = "오늘의 말씀 알림 설정 변경", description = "로그인한 사용자의 오늘의 말씀 알림 수신 설정을 저장합니다.")
    @PutMapping("/settings/daily-word") fun update(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: DailyWordNotificationSettingRequest): DailyWordNotificationSettingResponse
    @Operation(summary = "내 알림함 조회", description = "로그인한 사용자의 알림을 커서 기반으로 조회합니다.")
    @GetMapping fun inbox(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "다음 결과를 조회할 커서. 첫 요청에서는 생략합니다.") @RequestParam cursor: String?, @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "20") limit: Int): NotificationInboxPage
    @Operation(summary = "읽지 않은 알림 수", description = "로그인한 사용자의 미확인 알림 개수를 반환합니다.")
    @GetMapping("/unread-count") fun unreadCount(@AuthenticationPrincipal principal: JwtPrincipal): NotificationUnreadCount
    @Operation(summary = "알림 읽음 처리", description = "내 알림 한 건을 읽음 상태로 변경합니다.")
    @PostMapping("/{id}/read") fun markRead(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "읽음 처리할 본인 알림 UUID.") @PathVariable id: UUID): NotificationInboxItem
    @Operation(summary = "모든 알림 읽음 처리", description = "내 알림함의 읽지 않은 항목을 모두 읽음 상태로 변경합니다.")
    @PostMapping("/read-all") fun markAllRead(@AuthenticationPrincipal principal: JwtPrincipal): NotificationUnreadCount
    @Operation(summary = "알림 수신 확인", description = "본인 알림의 전달 식별자를 확인하고 수신 상태를 기록합니다.")
    @PostMapping("/deliveries/{deliveryId}/ack")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun acknowledgeDelivery(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "수신 확인할 본인 알림 전달 UUID.") @PathVariable deliveryId: UUID,
        @Valid @RequestBody request: NotificationDeliveryAcknowledgementRequest,
    )
}
