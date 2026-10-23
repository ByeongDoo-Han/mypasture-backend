package com.example.mypasture.prayer.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.prayer.application.PrayerService
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 인증된 사용자의 기도 편지 요청을 도메인 서비스로 전달합니다. */
@RestController
@RequestMapping("/api/v1/prayer")
class PrayerController(private val service: PrayerService) : PrayerApi {
    override fun profile(principal: JwtPrincipal) = service.profile(principal.userId)
    override fun setVerse(principal: JwtPrincipal, request: SetRepresentativeVerseRequest) =
        service.setRepresentativeVerse(principal.userId, request.version, request.bookCode, request.chapter, request.verse)
    override fun setReminder(principal: JwtPrincipal, request: SetReminderRequest) = service.setSundayReminder(principal.userId, request.enabled)
    override fun searchFriends(principal: JwtPrincipal, query: String) = service.searchUsers(principal.userId, query)
    override fun friends(principal: JwtPrincipal) = service.friends(principal.userId)
    override fun requestFriend(principal: JwtPrincipal, request: FriendRequest) = service.requestFriend(principal.userId, request.userId)
    override fun acceptFriend(principal: JwtPrincipal, friendshipId: UUID) = service.acceptFriend(principal.userId, friendshipId)
    override fun removeFriend(principal: JwtPrincipal, friendshipId: UUID) = service.removeFriend(principal.userId, friendshipId)
    override fun blockFriend(principal: JwtPrincipal, otherId: UUID) = service.blockFriend(principal.userId, otherId)
    override fun unblockFriend(principal: JwtPrincipal, otherId: UUID) = service.unblockFriend(principal.userId, otherId)
    override fun setFriendNotification(principal: JwtPrincipal, friendshipId: UUID, request: SetFriendNotificationRequest) =
        service.setFriendNotification(principal.userId, friendshipId, request.enabled)
    override fun myTopic(principal: JwtPrincipal) = service.myTopic(principal.userId)
    override fun saveTopic(principal: JwtPrincipal, request: SavePrayerTopicRequest) =
        service.saveTopic(principal.userId, request.content, request.visibleToFriends)
    override fun feed(principal: JwtPrincipal) = service.feed(principal.userId)
    override fun friendTopic(principal: JwtPrincipal, otherId: UUID) = service.friendTopic(principal.userId, otherId)
    override fun quota(principal: JwtPrincipal) = service.quota(principal.userId)
    override fun sendLetter(principal: JwtPrincipal, topicId: UUID, request: SendPrayerLetterRequest) =
        service.sendLetter(principal.userId, topicId, request.content)
    override fun letters(principal: JwtPrincipal, box: String) = service.letters(principal.userId, box)
    override fun createRewardIntent(principal: JwtPrincipal, request: CreatePrayerAdIntentRequest) =
        service.createAdIntent(principal.userId, request.platform).let { PrayerAdIntentResponse(it.id, it.adUnitId, it.expiresAt) }
    override fun notifications(principal: JwtPrincipal) = service.notifications(principal.userId)
    override fun readNotification(principal: JwtPrincipal, notificationId: UUID) = service.markNotificationRead(principal.userId, notificationId)
}
