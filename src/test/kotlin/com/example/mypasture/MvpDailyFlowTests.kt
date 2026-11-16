package com.example.mypasture

import com.example.mypasture.ai.application.AiSafetyException
import com.example.mypasture.ai.application.AskBibleAssistantUseCase
import com.example.mypasture.attendance.application.AttendanceService
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.daily_word.application.DailyWordService
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.notification.application.NotificationService
import com.example.mypasture.quiet_time.application.QuietTimeReflectionService
import com.example.mypasture.quiet_time.application.QuietTimeService
import com.example.mypasture.reading.application.ReadingService
import com.example.mypasture.reading.presentation.ReadingController
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import com.example.mypasture.testing.DailyWordFixtures
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 초기 MVP의 말씀 읽기, 일일 콘텐츠, 현장 QT, 출석, AI 안전 정책을 검증합니다. */
@SpringBootTest
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true"])
class MvpDailyFlowTests(
    @Autowired private val userRepository: UserRepository,
    @Autowired private val readingService: ReadingService,
    @Autowired private val readingController: ReadingController,
    @Autowired private val dailyWordService: DailyWordService,
    @Autowired private val dailyWordRepository: DailyWordRepository,
    @Autowired private val bibleBookRepository: BibleBookEntityRepository,
    @Autowired private val bibleVerseRepository: BibleVerseEntityRepository,
    @Autowired private val notificationService: NotificationService,
    @Autowired private val quietTimeService: QuietTimeService,
    @Autowired private val reflectionService: QuietTimeReflectionService,
    @Autowired private val attendanceService: AttendanceService,
    @Autowired private val aiUseCase: AskBibleAssistantUseCase,
) {
    @Test fun `읽기 진행 상태를 갱신하고 일일 기록을 남긴다`() {
        val user = newUser()
        readingService.record(user.id, "KOR1910", "GEN", 1, 1, false, "Asia/Seoul")
        readingService.record(user.id, "KOR1910", "GEN", 1, 2, true, "Asia/Seoul")
        val state = readingService.states(user.id).single()
        assertTrue(state.completed); assertEquals(2, state.lastVerse)
        assertEquals(1, readingService.history(user.id, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)).size)
        val principal = JwtPrincipal(user.id, user.email, user.displayName, UserRole.USER, SubscriptionTier.FREE, user.authVersion)
        assertEquals("창세기", readingController.progress(principal).single().bookName)
    }

    @Test fun `오늘의 말씀은 한 번 생성해 모든 사용자에게 공유한다`() {
        val date = LocalDate.of(2026, 8, 14)
        val published = DailyWordFixtures.published(date, dailyWordRepository, bibleBookRepository, bibleVerseRepository)
        assertEquals(published.id, dailyWordService.getPublished(date).id)
        assertEquals(published.id, dailyWordService.getPublished(date).id)
    }

    @Test fun `오늘의 말씀 알림 설정은 시간대를 검증하고 저장한다`() {
        val user = newUser()
        val setting = notificationService.updateSetting(user.id, true, LocalTime.of(8, 0), "Asia/Seoul")
        assertTrue(setting.enabled); assertEquals(LocalTime.of(8, 0), notificationService.setting(user.id).notificationTime)
    }

    @Test fun `인증한 QR 참여는 출석을 한 건 만들고 묵상 나눔을 허용한다`() {
        val user = newUser()
        val pasture = quietTimeService.createPasture(user.id, "테스트 목장", "리더")
        val session = quietTimeService.createOfflineSession(user.id, pasture.id, null, null, "불안", null, null, null, null)
        quietTimeService.joinOfflineSession(session.invitation.offlineCode, "목원", "device-${UUID.randomUUID()}", user.id)
        quietTimeService.joinOfflineSession(session.invitation.offlineCode, "목원", "device-${UUID.randomUUID()}", user.id)
        val reflection = reflectionService.save(session.session.id, user.id, "말씀을 통해 염려를 기도로 맡기기로 했다.", true)
        assertTrue(reflection.sharedWithPasture)
        assertEquals(1, attendanceService.summary(user.id, LocalDate.now().minusDays(1), LocalDate.now().plusDays(1)).records.size)
    }

    @Test fun `위기 표현은 AI 제공자 호출 전에 긴급 대응으로 분류한다`() {
        val user = newUser()
        assertThrows<AiSafetyException> { aiUseCase.ask(user.id, SubscriptionTier.FREE, "죽고 싶다는 생각이 들어") }
    }

    private fun newUser() = userRepository.save(UserEntity(email = "mvp-${UUID.randomUUID()}@example.com", displayName = "MVP 사용자"))
}
