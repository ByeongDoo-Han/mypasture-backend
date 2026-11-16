package com.example.mypasture.prayer.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.prayer.application.PrayerFriendView
import com.example.mypasture.prayer.application.PrayerLetterView
import com.example.mypasture.prayer.application.PrayerNotificationView
import com.example.mypasture.prayer.application.PrayerProfileView
import com.example.mypasture.prayer.application.PrayerQuotaView
import com.example.mypasture.prayer.application.PrayerTopicView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.UUID

data class SetRepresentativeVerseRequest(@field:NotBlank val version: String, @field:NotBlank val bookCode: String, val chapter: Int, val verse: Int)
data class SetReminderRequest(val enabled: Boolean)
data class FriendRequest(val userId: UUID)
data class SetFriendNotificationRequest(val enabled: Boolean)
data class SavePrayerTopicRequest(@field:NotBlank @field:Size(max = 2000) val content: String, val visibleToFriends: Boolean)
data class SendPrayerLetterRequest(@field:NotBlank @field:Size(max = 2000) val content: String)
data class CreatePrayerAdIntentRequest(@field:NotBlank val platform: String)
data class PrayerAdIntentResponse(val id: UUID, val adUnitId: String, val expiresAt: java.time.Instant)

/** 기도 프로필·친구·주간 기도제목·편지 API의 공개 계약입니다. */
@Tag(name = "기도 편지", description = "친구의 주간 기도제목과 기도 편지를 관리합니다.")
interface PrayerApi {
    @Operation(summary = "내 기도 프로필 조회", description = "로그인한 사용자의 대표 말씀과 일요일 알림 설정을 조회합니다.")
    @GetMapping("/profile")
    fun profile(@AuthenticationPrincipal principal: JwtPrincipal): PrayerProfileView

    @Operation(summary = "대표 말씀 설정", description = "실제 존재하는 성경 한 절을 본인의 대표 말씀으로 저장합니다.")
    @PutMapping("/profile/verse")
    fun setVerse(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: SetRepresentativeVerseRequest): PrayerProfileView

    @Operation(summary = "일요일 기도제목 알림 설정", description = "본인의 주간 기도제목 작성 알림 수신 여부를 저장합니다.")
    @PutMapping("/profile/reminder")
    fun setReminder(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: SetReminderRequest): PrayerProfileView

    @Operation(summary = "친구 닉네임 검색", description = "가입자의 닉네임에서 친구 요청 대상을 찾습니다.")
    @GetMapping("/friends/search")
    fun searchFriends(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "검색할 닉네임의 일부, 2~20자.") @RequestParam query: String): List<PrayerFriendView>

    @Operation(summary = "친구와 받은 요청 목록", description = "본인의 친구 관계와 받은 요청을 조회합니다.")
    @GetMapping("/friends")
    fun friends(@AuthenticationPrincipal principal: JwtPrincipal): List<PrayerFriendView>

    @Operation(summary = "친구 요청", description = "다른 가입자에게 기도 친구 요청을 보냅니다.")
    @PostMapping("/friends/requests")
    @ResponseStatus(HttpStatus.CREATED)
    fun requestFriend(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: FriendRequest): PrayerFriendView

    @Operation(summary = "친구 요청 수락", description = "본인에게 도착한 친구 요청을 수락합니다.")
    @PostMapping("/friends/{friendshipId}/accept")
    fun acceptFriend(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "대상 친구 관계 UUID.") @PathVariable friendshipId: UUID): PrayerFriendView

    @Operation(summary = "친구 관계 해제", description = "본인이 속한 친구 요청 또는 관계를 해제합니다.")
    @DeleteMapping("/friends/{friendshipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun removeFriend(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "대상 친구 관계 UUID.") @PathVariable friendshipId: UUID)

    @Operation(summary = "기도 친구 차단", description = "상대의 기도제목 노출과 친구 관계를 차단합니다.")
    @PostMapping("/friends/users/{otherId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun blockFriend(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "대상 가입자 UUID.") @PathVariable otherId: UUID)

    @Operation(summary = "기도 친구 차단 해제", description = "본인이 차단한 상대를 다시 친구 요청 가능한 상태로 되돌립니다.")
    @DeleteMapping("/friends/users/{otherId}/block")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unblockFriend(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "차단 해제할 가입자 UUID.") @PathVariable otherId: UUID)

    @Operation(summary = "친구 기도제목 알림 설정", description = "수락한 친구의 새 기도제목 알림 수신 여부를 저장합니다.")
    @PutMapping("/friends/{friendshipId}/notifications")
    fun setFriendNotification(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "대상 친구 관계 UUID.") @PathVariable friendshipId: UUID, @Valid @RequestBody request: SetFriendNotificationRequest): PrayerFriendView

    @Operation(summary = "이번 주 내 기도제목 조회", description = "한국 시간 일요일부터 시작하는 이번 주 본인 기도제목을 조회합니다.")
    @GetMapping("/topics/me")
    fun myTopic(@AuthenticationPrincipal principal: JwtPrincipal): PrayerTopicView?

    @Operation(summary = "이번 주 기도제목 작성 또는 수정", description = "이번 주 본인 기도제목과 친구 공개 여부를 저장합니다.")
    @PutMapping("/topics/me")
    fun saveTopic(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: SavePrayerTopicRequest): PrayerTopicView

    @Operation(summary = "친구의 공개 기도제목 목록", description = "친구 관계를 수락하고 공개된 이번 주 기도제목만 조회합니다.")
    @GetMapping("/topics/feed")
    fun feed(@AuthenticationPrincipal principal: JwtPrincipal): List<PrayerTopicView>

    @Operation(summary = "친구의 공개 기도제목 조회", description = "특정 친구가 공개한 이번 주 기도제목을 조회합니다.")
    @GetMapping("/topics/friends/{otherId}")
    fun friendTopic(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "대상 가입자 UUID.") @PathVariable otherId: UUID): PrayerTopicView

    @Operation(summary = "기도 편지 주간 발송권 조회", description = "기본 3명과 검증된 광고 보상을 포함한 이번 주 발송 가능 인원을 조회합니다.")
    @GetMapping("/quota")
    fun quota(@AuthenticationPrincipal principal: JwtPrincipal): PrayerQuotaView

    @Operation(summary = "친구에게 기도 편지 전송", description = "친구의 공개 기도제목에 이번 주 한 통의 편지를 보냅니다.")
    @PostMapping("/topics/{topicId}/letters")
    @ResponseStatus(HttpStatus.CREATED)
    fun sendLetter(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "이번 주 공개 기도제목 UUID.") @PathVariable topicId: UUID, @Valid @RequestBody request: SendPrayerLetterRequest): PrayerLetterView

    @Operation(summary = "받은 또는 보낸 기도 편지 조회", description = "본인이 받은 편지 또는 보낸 편지를 조회합니다.")
    @GetMapping("/letters")
    fun letters(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "받은 편지는 RECEIVED, 보낸 편지는 SENT.") @RequestParam(defaultValue = "RECEIVED") box: String): List<PrayerLetterView>

    @Operation(summary = "추가 편지 발송권 광고 요청", description = "AdMob 보상형 광고의 서버 검증에 사용할 짧은 요청 ID를 생성합니다.")
    @PostMapping("/reward-intents")
    @ResponseStatus(HttpStatus.CREATED)
    fun createRewardIntent(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: CreatePrayerAdIntentRequest): PrayerAdIntentResponse

    @Operation(summary = "기도 알림 목록", description = "본인에게 도착한 기도제목과 편지 알림을 조회합니다.")
    @GetMapping("/notifications")
    fun notifications(@AuthenticationPrincipal principal: JwtPrincipal): List<PrayerNotificationView>

    @Operation(summary = "기도 알림 읽음 처리", description = "본인에게 도착한 기도 알림 한 건을 읽음으로 표시합니다.")
    @PostMapping("/notifications/{notificationId}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun readNotification(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "본인 기도 알림 UUID.") @PathVariable notificationId: UUID)
}
