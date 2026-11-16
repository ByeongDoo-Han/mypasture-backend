package com.example.mypasture.user.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.reading.infrastructure.BibleReadingHistoryRepository
import com.example.mypasture.user.domain.UserVisitEntity
import com.example.mypasture.user.infrastructure.UserVisitRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 서버 날짜로 방문을 기록하고 기존 읽기 이력을 연도별 활동으로 집계합니다. */
@Service
class ActivityCalendarService(
    private val users: UserRepository,
    private val visits: UserVisitRepository,
    private val reading: BibleReadingHistoryRepository,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun visit(userId: UUID): LocalDate {
        // 사용자 행 잠금과 unique constraint를 함께 사용해 여러 기기의 동시 요청도 멱등 처리합니다.
        checkNotNull(users.findActiveByIdForUpdate(userId)) { "Active user is required" }
        val now = Instant.now(clock)
        val today = LocalDate.ofInstant(now, ZONE)
        if (!visits.existsByUserIdAndVisitedOn(userId, today)) {
            visits.save(UserVisitEntity(userId = userId, visitedOn = today, createdAt = now))
        }
        return today
    }

    @Transactional(readOnly = true)
    fun calendar(userId: UUID, requestedYear: Int?): ActivityCalendar {
        val today = LocalDate.now(clock.withZone(ZONE))
        val year = requestedYear ?: today.year
        require(year in 2000..today.year) { "Year must be between 2000 and the current year" }
        val from = LocalDate.of(year, 1, 1)
        val to = minOf(LocalDate.of(year, 12, 31), today)
        val visited = visits.findAllByUserIdAndVisitedOnBetween(userId, from, to).map { it.visitedOn }.toSet()
        val histories = reading.findAllByUserIdAndReadOnBetweenOrderByReadOnDescUpdatedAtDesc(userId, from, to).groupBy { it.readOn }
        val days = from.datesUntil(to.plusDays(1)).map { date ->
            // 번역본/절 위치를 바꾸어도 같은 성경 장은 하루 한 번만 집계합니다.
            val chapters = histories[date].orEmpty().groupBy { it.bookCode to it.chapter }.values
            ActivityDay(date, date in visited, chapters.size, chapters.count { chapter -> chapter.any { it.completed } })
        }.toList()
        return ActivityCalendar(year, ZONE.id, today, days)
    }

    companion object { val ZONE: ZoneId = ZoneId.of("Asia/Seoul") }
}

/** 미래 날짜를 제외한 한 해의 활동 집계이며 과거 방문을 추정해서 만들지 않습니다. */
data class ActivityCalendar(val year: Int, val timezone: String, val today: LocalDate, val days: List<ActivityDay>)

/** 하루에 방문했는지와 읽기 기록이 있는 장 수, 완독한 장 수입니다. */
data class ActivityDay(val date: LocalDate, val visited: Boolean, val chaptersRead: Int, val chaptersCompleted: Int)
