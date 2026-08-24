package com.example.mypasture.notification.presentation

import com.example.mypasture.notification.application.NotificationOperationsService
import com.example.mypasture.notification.domain.NotificationType
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** ADMIN 권한으로 알림 broadcast, provider 전송과 앱 열람 상태를 조회합니다. */
@RestController
class NotificationAdminController(
    private val service: NotificationOperationsService,
) : NotificationAdminApi {
    override fun operations(type: NotificationType?, from: Instant?, to: Instant?, page: Int, size: Int) =
        service.operations(type, from, to, page, size)
}
