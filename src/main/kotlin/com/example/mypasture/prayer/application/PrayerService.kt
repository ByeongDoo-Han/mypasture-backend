package com.example.mypasture.prayer.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.prayer.domain.PrayerAdIntentEntity
import com.example.mypasture.prayer.domain.PrayerFriendshipEntity
import com.example.mypasture.prayer.domain.PrayerLetterEntity
import com.example.mypasture.prayer.domain.PrayerNotificationEntity
import com.example.mypasture.prayer.domain.PrayerProfileEntity
import com.example.mypasture.prayer.domain.WeeklyPrayerTopicEntity
import com.example.mypasture.prayer.infrastructure.PrayerAdIntentRepository
import com.example.mypasture.prayer.infrastructure.PrayerFriendshipRepository
import com.example.mypasture.prayer.infrastructure.PrayerLetterRepository
import com.example.mypasture.prayer.infrastructure.PrayerNotificationRepository
import com.example.mypasture.prayer.infrastructure.PrayerProfileRepository
import com.example.mypasture.prayer.infrastructure.WeeklyPrayerTopicRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** 사용자에게 노출할 대표 말씀 식별자입니다. */
data class PrayerVerseView(val version: String, val bookCode: String, val bookName: String, val chapter: Int, val verse: Int, val text: String)

/** 한 주 기도제목의 작성자와 공개 상태입니다. */
data class PrayerTopicView(val id: UUID, val userId: UUID, val displayName: String, val weekStart: LocalDate, val content: String, val visibleToFriends: Boolean, val updatedAt: Instant)

/** 사용자 기도 프로필과 일요일 알림 선택입니다. */
data class PrayerProfileView(val representativeVerse: PrayerVerseView?, val sundayReminderEnabled: Boolean)

/** 친구 관계의 본인 관점과 상대의 이번 주 기도제목입니다. */
data class PrayerFriendView(val id: UUID, val userId: UUID, val displayName: String, val status: String, val incoming: Boolean, val notifyNewTopic: Boolean, val representativeVerse: PrayerVerseView?, val topic: PrayerTopicView?)

/** 보낸/받은 편지와 표시 이름입니다. */
data class PrayerLetterView(val id: UUID, val topicId: UUID, val senderId: UUID, val senderName: String, val recipientId: UUID, val recipientName: String, val content: String, val createdAt: Instant)

/** 주간 발송 가능 인원과 광고 보상 상태입니다. */
data class PrayerQuotaView(val weekStart: LocalDate, val base: Int, val bonus: Int, val used: Int, val dailyAdsUsed: Int, val dailyAdsLimit: Int, val adsEnabled: Boolean) {
    val remaining: Int get() = (base + bonus - used).coerceAtLeast(0)
}

/** 앱 내 기도 알림의 안전한 요약입니다. */
data class PrayerNotificationView(val id: UUID, val kind: String, val relatedId: UUID?, val title: String, val body: String, val createdAt: Instant, val read: Boolean)

/** 친구 관계 또는 공개 범위로 사용할 수 없는 자료입니다. */
class PrayerNotFoundException : RuntimeException("기도 자료를 찾을 수 없습니다.")
/** 현재 상태에서 중복·역방향 요청을 허용하지 않습니다. */
class PrayerConflictException(message: String) : RuntimeException(message)
/** 주간 인원 또는 일일 광고 횟수를 모두 사용했습니다. */
class PrayerLimitException(message: String) : RuntimeException(message)

/** 대표 말씀, 친구, 주간 기도제목, 편지 및 보상 한도를 조합합니다. */
@Service
class PrayerService(
    private val users: UserRepository,
    private val books: BibleBookEntityRepository,
    private val verses: BibleVerseEntityRepository,
    private val profiles: PrayerProfileRepository,
    private val friendships: PrayerFriendshipRepository,
    private val topics: WeeklyPrayerTopicRepository,
    private val letters: PrayerLetterRepository,
    private val adIntents: PrayerAdIntentRepository,
    private val notifications: PrayerNotificationRepository,
    private val ads: PrayerAdsConfig,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val seoul = ZoneId.of("Asia/Seoul")

    fun today(): LocalDate = LocalDate.now(clock.withZone(seoul))
    fun weekStart(day: LocalDate = today()): LocalDate = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))

    @Transactional(readOnly = true)
    fun profile(userId: UUID): PrayerProfileView {
        val profile = profiles.findById(userId).orElse(null)
        return PrayerProfileView(profile?.representativeVerseId?.let(::verseView), profile?.sundayReminderEnabled ?: false)
    }

    @Transactional
    fun setRepresentativeVerse(userId: UUID, version: String, bookCode: String, chapter: Int, verse: Int): PrayerProfileView {
        require(chapter > 0 && verse > 0) { "장과 절은 1 이상이어야 합니다." }
        val normalizedVersion = version.trim().uppercase()
        val book = books.findByVersionAndCode(normalizedVersion, bookCode.trim().uppercase()) ?: throw PrayerNotFoundException()
        val reference = verses.findByVersionAndBook_IdAndChapterAndVerse(normalizedVersion, book.id, chapter, verse) ?: throw PrayerNotFoundException()
        lockUser(userId)
        val profile = profiles.findById(userId).orElseGet { PrayerProfileEntity(userId) }
        profile.representativeVerseId = reference.id
        profile.updatedAt = Instant.now(clock)
        profiles.save(profile)
        return PrayerProfileView(verseView(reference.id), profile.sundayReminderEnabled)
    }

    @Transactional
    fun setSundayReminder(userId: UUID, enabled: Boolean): PrayerProfileView {
        lockUser(userId)
        val profile = profiles.findById(userId).orElseGet { PrayerProfileEntity(userId) }
        profile.sundayReminderEnabled = enabled
        profile.updatedAt = Instant.now(clock)
        profiles.save(profile)
        return PrayerProfileView(profile.representativeVerseId?.let(::verseView), enabled)
    }

    @Transactional(readOnly = true)
    fun searchUsers(userId: UUID, query: String): List<PrayerFriendView> {
        val key = query.trim()
        require(key.length in 2..20) { "닉네임 검색어는 2~20자여야 합니다." }
        return users.searchActiveDisplayNames(key, PageRequest.of(0, 20))
            .filter { it.id != userId && findPair(userId, it.id)?.status != "BLOCKED" }
            .map { user ->
                val friend = findPair(userId, user.id)
                friendView(userId, user.id, user.displayName, friend)
            }
    }

    @Transactional(readOnly = true)
    fun friends(userId: UUID): List<PrayerFriendView> =
        friendships.findForUser(userId).filter { it.status != "BLOCKED" || it.blockedBy == userId }.mapNotNull { friend ->
            val other = users.findByIdAndDeletedAtIsNull(friend.other(userId)) ?: return@mapNotNull null
            friendView(userId, other.id, other.displayName, friend)
        }

    @Transactional
    fun requestFriend(userId: UUID, otherId: UUID): PrayerFriendView {
        require(userId != otherId) { "본인에게 친구 요청을 보낼 수 없습니다." }
        lockUsers(userId, otherId)
        val other = users.findByIdAndDeletedAtIsNull(otherId) ?: throw PrayerNotFoundException()
        val pair = pair(userId, otherId)
        val existing = friendships.findPairForUpdate(pair.first, pair.second)
        if (existing != null) throw PrayerConflictException("이미 친구 요청 또는 차단 관계가 있습니다.")
        val now = Instant.now(clock)
        val friend = friendships.saveAndFlush(PrayerFriendshipEntity(userLow = pair.first, userHigh = pair.second, requestedBy = userId, createdAt = now, updatedAt = now))
        enqueue(otherId, "FRIEND_REQUEST", friend.id, "친구 요청이 도착했어요", "목장성경에서 새 친구 요청을 확인해 주세요.", "friend-request:${friend.id}")
        return friendView(userId, otherId, other.displayName, friend)
    }

    @Transactional
    fun acceptFriend(userId: UUID, friendshipId: UUID): PrayerFriendView {
        val requested = friendships.findById(friendshipId).orElseThrow(::PrayerNotFoundException)
        if (requested.userLow != userId && requested.userHigh != userId) throw PrayerNotFoundException()
        lockUsers(requested.userLow, requested.userHigh)
        val friend = friendships.findPairForUpdate(requested.userLow, requested.userHigh) ?: throw PrayerNotFoundException()
        if (friend.status != "PENDING" || friend.requestedBy == userId) throw PrayerConflictException("수락할 수 있는 친구 요청이 없습니다.")
        friend.status = "ACCEPTED"
        friend.updatedAt = Instant.now(clock)
        enqueue(friend.requestedBy, "FRIEND_ACCEPTED", friend.id, "친구가 되었어요", "새 친구의 공개 기도제목을 확인할 수 있어요.", "friend-accepted:${friend.id}")
        val other = users.findByIdAndDeletedAtIsNull(friend.other(userId)) ?: throw PrayerNotFoundException()
        return friendView(userId, other.id, other.displayName, friend)
    }

    @Transactional
    fun removeFriend(userId: UUID, friendshipId: UUID) {
        val friend = friendships.findById(friendshipId).orElseThrow(::PrayerNotFoundException)
        if (friend.userLow != userId && friend.userHigh != userId) throw PrayerNotFoundException()
        lockUsers(friend.userLow, friend.userHigh)
        friendships.delete(friendships.findPairForUpdate(friend.userLow, friend.userHigh) ?: throw PrayerNotFoundException())
    }

    @Transactional
    fun blockFriend(userId: UUID, otherId: UUID) {
        require(userId != otherId)
        lockUsers(userId, otherId)
        if (users.findByIdAndDeletedAtIsNull(otherId) == null) throw PrayerNotFoundException()
        val (low, high) = pair(userId, otherId)
        val friend = friendships.findPairForUpdate(low, high)
            ?: PrayerFriendshipEntity(userLow = low, userHigh = high, requestedBy = userId)
        friend.status = "BLOCKED"
        friend.blockedBy = userId
        friend.notifyLow = false
        friend.notifyHigh = false
        friend.updatedAt = Instant.now(clock)
        friendships.save(friend)
    }

    @Transactional
    fun unblockFriend(userId: UUID, otherId: UUID) {
        lockUsers(userId, otherId)
        val (low, high) = pair(userId, otherId)
        val friend = friendships.findPairForUpdate(low, high) ?: throw PrayerNotFoundException()
        if (friend.status != "BLOCKED" || friend.blockedBy != userId) throw PrayerNotFoundException()
        friendships.delete(friend)
    }

    @Transactional
    fun setFriendNotification(userId: UUID, friendshipId: UUID, enabled: Boolean): PrayerFriendView {
        val friend = friendships.findById(friendshipId).orElseThrow(::PrayerNotFoundException)
        if (friend.status != "ACCEPTED" || (friend.userLow != userId && friend.userHigh != userId)) throw PrayerNotFoundException()
        lockUser(userId)
        friend.setNotifies(userId, enabled)
        friend.updatedAt = Instant.now(clock)
        val other = users.findByIdAndDeletedAtIsNull(friend.other(userId)) ?: throw PrayerNotFoundException()
        return friendView(userId, other.id, other.displayName, friend)
    }

    @Transactional
    fun saveTopic(userId: UUID, content: String, visible: Boolean): PrayerTopicView {
        val normalized = content.trim()
        require(normalized.length in 1..2000) { "기도제목은 1~2000자여야 합니다." }
        lockUser(userId)
        val week = weekStart()
        val now = Instant.now(clock)
        val topic = topics.findByUserIdAndWeekStart(userId, week)?.also {
            val previouslyVisible = it.visibleToFriends
            it.content = normalized
            it.visibleToFriends = visible
            it.updatedAt = now
            if (!previouslyVisible && visible) notifyFriendsForTopic(userId, it.id)
        } ?: topics.saveAndFlush(WeeklyPrayerTopicEntity(userId = userId, weekStart = week, content = normalized, visibleToFriends = visible, createdAt = now, updatedAt = now)).also {
            if (visible) notifyFriendsForTopic(userId, it.id)
        }
        return topicView(topic)
    }

    @Transactional(readOnly = true)
    fun myTopic(userId: UUID): PrayerTopicView? = topics.findByUserIdAndWeekStart(userId, weekStart())?.let(::topicView)

    @Transactional(readOnly = true)
    fun friendTopic(userId: UUID, otherId: UUID): PrayerTopicView {
        if (findPair(userId, otherId)?.status != "ACCEPTED") throw PrayerNotFoundException()
        val topic = topics.findByUserIdAndWeekStart(otherId, weekStart()) ?: throw PrayerNotFoundException()
        if (!topic.visibleToFriends) throw PrayerNotFoundException()
        return topicView(topic)
    }

    @Transactional(readOnly = true)
    fun feed(userId: UUID): List<PrayerTopicView> =
        friendships.findAcceptedForUser(userId).mapNotNull { friend ->
            val otherId = friend.other(userId)
            topics.findByUserIdAndWeekStart(otherId, weekStart())?.takeIf { it.visibleToFriends && users.findByIdAndDeletedAtIsNull(otherId) != null }?.let(::topicView)
        }

    @Transactional(readOnly = true)
    fun quota(userId: UUID): PrayerQuotaView {
        val week = weekStart()
        return PrayerQuotaView(week, 3, adIntents.countByUserIdAndWeekStartAndClaimedAtIsNotNull(userId, week).toInt(),
            letters.countBySenderIdAndWeekStart(userId, week).toInt(),
            adIntents.countByUserIdAndRewardDayAndClaimedAtIsNotNull(userId, today()).toInt(), 3, ads.enabled)
    }

    @Transactional
    fun sendLetter(userId: UUID, topicId: UUID, content: String): PrayerLetterView {
        val normalized = content.trim()
        require(normalized.length in 1..2000) { "기도 편지는 1~2000자여야 합니다." }
        lockUser(userId)
        val topic = topics.findByIdForUpdate(topicId) ?: throw PrayerNotFoundException()
        if (topic.userId == userId || topic.weekStart != weekStart() || !topic.visibleToFriends ||
            findPairForUpdate(userId, topic.userId)?.status != "ACCEPTED") throw PrayerNotFoundException()
        if (letters.existsBySenderIdAndRecipientIdAndWeekStart(userId, topic.userId, topic.weekStart))
            throw PrayerConflictException("이번 주에는 이미 이 친구에게 편지를 보냈습니다.")
        val quota = quota(userId)
        if (quota.remaining <= 0) throw PrayerLimitException("이번 주 편지 발송권을 모두 사용했습니다.")
        val letter = letters.saveAndFlush(PrayerLetterEntity(topicId = topicId, senderId = userId,
            recipientId = topic.userId, weekStart = topic.weekStart, content = normalized, createdAt = Instant.now(clock)))
        enqueue(topic.userId, "PRAYER_LETTER", letter.id, "기도 편지가 도착했어요", "친구가 보낸 기도 편지를 확인해 주세요.", "prayer-letter:${letter.id}")
        return letterView(letter)
    }

    @Transactional(readOnly = true)
    fun letters(userId: UUID, box: String): List<PrayerLetterView> {
        val page = PageRequest.of(0, 100)
        val found = when (box) {
            "RECEIVED" -> letters.findByRecipientIdOrderByCreatedAtDesc(userId, page)
            "SENT" -> letters.findBySenderIdOrderByCreatedAtDesc(userId, page)
            else -> throw IllegalArgumentException("편지함 종류가 올바르지 않습니다.")
        }
        return found.map(::letterView)
    }

    @Transactional(readOnly = true)
    fun notifications(userId: UUID): List<PrayerNotificationView> =
        notifications.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 100))
            .map { PrayerNotificationView(it.id, it.kind, it.relatedId, it.title, it.body, it.createdAt, it.readAt != null) }

    @Transactional
    fun markNotificationRead(userId: UUID, id: UUID) {
        val item = notifications.findByIdAndUserId(id, userId) ?: throw PrayerNotFoundException()
        if (item.readAt == null) item.readAt = Instant.now(clock)
    }

    @Transactional
    fun createAdIntent(userId: UUID, platform: String): PrayerAdIntentEntity {
        if (!ads.enabled) throw PrayerConflictException("광고 발송권은 현재 사용할 수 없습니다.")
        lockUser(userId)
        val unit = ads.unitFor(platform)
        if (quota(userId).dailyAdsUsed >= 3) throw PrayerLimitException("오늘의 광고 시청 횟수를 모두 사용했습니다.")
        val now = Instant.now(clock)
        return adIntents.save(PrayerAdIntentEntity(userId = userId, weekStart = weekStart(), rewardDay = today(), adUnitId = unit,
            expiresAt = now.plusSeconds(900), createdAt = now))
    }

    @Transactional
    fun claimAdReward(intentId: UUID, transactionId: String, adUnitId: String, signedAt: Instant) {
        if (!ads.enabled) throw PrayerConflictException("광고 보상이 비활성화되었습니다.")
        val intent = adIntents.findById(intentId).orElseThrow(::PrayerNotFoundException)
        lockUser(intent.userId)
        if (intent.claimedAt != null) {
            if (intent.transactionId == transactionId) return
            throw PrayerConflictException("이미 사용한 광고 요청입니다.")
        }
        if (adIntents.existsByTransactionId(transactionId)) throw PrayerConflictException("이미 반영된 광고입니다.")
        val now = Instant.now(clock)
        if (intent.adUnitId != adUnitId || signedAt.isBefore(intent.createdAt.minusSeconds(60)) ||
            signedAt.isAfter(intent.expiresAt) || now.isAfter(intent.expiresAt.plusSeconds(300)))
            throw PrayerConflictException("광고 보상 요청이 만료되었거나 일치하지 않습니다.")
        if (adIntents.countByUserIdAndRewardDayAndClaimedAtIsNotNull(intent.userId, intent.rewardDay) >= 3)
            throw PrayerLimitException("오늘의 광고 시청 횟수를 모두 사용했습니다.")
        intent.claimedAt = now
        intent.transactionId = transactionId
        adIntents.saveAndFlush(intent)
    }

    private fun notifyFriendsForTopic(userId: UUID, topicId: UUID) {
        friendships.findAcceptedForUser(userId).forEach { friend ->
            val recipient = friend.other(userId)
            if (friend.notifies(recipient) && users.findByIdAndDeletedAtIsNull(recipient) != null)
                enqueue(recipient, "NEW_PRAYER_TOPIC", topicId, "친구의 이번 주 기도제목", "새로운 기도제목을 확인해 주세요.", "new-prayer-topic:${topicId}:${recipient}")
        }
    }

    private fun enqueue(userId: UUID, kind: String, relatedId: UUID?, title: String, body: String, eventKey: String) {
        if (!notifications.existsByEventKey(eventKey))
            notifications.save(PrayerNotificationEntity(eventKey = eventKey, userId = userId, kind = kind, relatedId = relatedId,
                title = title, body = body, createdAt = Instant.now(clock), nextAttemptAt = Instant.now(clock)))
    }

    private fun topicView(topic: WeeklyPrayerTopicEntity): PrayerTopicView {
        val name = users.findByIdAndDeletedAtIsNull(topic.userId)?.displayName ?: "탈퇴한 사용자"
        return PrayerTopicView(topic.id, topic.userId, name, topic.weekStart, topic.content, topic.visibleToFriends, topic.updatedAt)
    }

    private fun friendView(userId: UUID, otherId: UUID, name: String, friend: PrayerFriendshipEntity?): PrayerFriendView {
        val topic = if (friend?.status == "ACCEPTED") topics.findByUserIdAndWeekStart(otherId, weekStart())?.takeIf { it.visibleToFriends }?.let(::topicView) else null
        return PrayerFriendView(friend?.id ?: UUID(0, 0), otherId, name, friend?.status ?: "NONE",
            friend?.status == "PENDING" && friend.requestedBy != userId, friend?.notifies(userId) ?: false,
            if (friend?.status == "BLOCKED") null else profiles.findById(otherId).orElse(null)?.representativeVerseId?.let(::verseView), topic)
    }

    private fun letterView(letter: PrayerLetterEntity) = PrayerLetterView(letter.id, letter.topicId, letter.senderId,
        users.findByIdAndDeletedAtIsNull(letter.senderId)?.displayName ?: "탈퇴한 사용자", letter.recipientId,
        users.findByIdAndDeletedAtIsNull(letter.recipientId)?.displayName ?: "탈퇴한 사용자", letter.content, letter.createdAt)

    private fun verseView(id: Long): PrayerVerseView? = verses.findById(id).orElse(null)?.let {
        PrayerVerseView(it.version, it.book.code, it.book.name, it.chapter, it.verse, it.text)
    }

    private fun findPair(left: UUID, right: UUID): PrayerFriendshipEntity? {
        val (low, high) = pair(left, right)
        return friendships.findByUserLowAndUserHigh(low, high)
    }

    private fun findPairForUpdate(left: UUID, right: UUID): PrayerFriendshipEntity? {
        val (low, high) = pair(left, right)
        return friendships.findPairForUpdate(low, high)
    }

    private fun pair(left: UUID, right: UUID): Pair<UUID, UUID> =
        if (left.toString() <= right.toString()) left to right else right to left

    private fun lockUser(userId: UUID) {
        if (users.findActiveByIdForUpdate(userId) == null) throw PrayerNotFoundException()
    }

    private fun lockUsers(left: UUID, right: UUID) {
        val (low, high) = pair(left, right)
        lockUser(low)
        lockUser(high)
    }
}
