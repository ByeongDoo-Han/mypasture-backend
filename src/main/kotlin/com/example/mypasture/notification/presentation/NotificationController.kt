package com.example.mypasture.notification.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.notification.application.NotificationService
import com.example.mypasture.notification.application.NotificationInboxService
import com.example.mypasture.notification.application.NotificationUnreadCount
import com.example.mypasture.notification.application.NotificationDeliveryAcknowledgementService
import com.example.mypasture.notification.domain.DailyWordNotificationSettingEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 인증 사용자의 푸시 기기와 일일 알림 설정을 관리합니다. */
@RestController @RequestMapping("/api/v1/notifications")
class NotificationController(
    private val service: NotificationService,
    private val inboxService: NotificationInboxService,
    private val acknowledgementService: NotificationDeliveryAcknowledgementService,
) : NotificationApi {
    override fun register(principal: JwtPrincipal, request: RegisterDeviceRequest) { service.register(principal.userId, request.deviceId, request.fcmToken, request.platform) }
    override fun unregister(principal: JwtPrincipal, deviceId: String) = service.unregister(principal.userId, deviceId)
    override fun setting(principal: JwtPrincipal) = service.setting(principal.userId).toResponse()
    override fun update(principal: JwtPrincipal, request: DailyWordNotificationSettingRequest) = service.updateSetting(principal.userId, request.enabled, request.notificationTime, request.timezone).toResponse()
    override fun inbox(principal: JwtPrincipal, cursor: String?, limit: Int) = inboxService.list(principal.userId, cursor, limit)
    override fun unreadCount(principal: JwtPrincipal) = NotificationUnreadCount(inboxService.unreadCount(principal.userId))
    override fun markRead(principal: JwtPrincipal, id: java.util.UUID) = inboxService.markRead(principal.userId, id)
    override fun markAllRead(principal: JwtPrincipal) = inboxService.markAllRead(principal.userId)
    override fun acknowledgeDelivery(
        principal: JwtPrincipal,
        deliveryId: java.util.UUID,
        request: NotificationDeliveryAcknowledgementRequest,
    ) = acknowledgementService.acknowledge(principal.userId, deliveryId, request.deviceId, request.event)
}
private fun DailyWordNotificationSettingEntity.toResponse() = DailyWordNotificationSettingResponse(enabled, notificationTime, timezone)
