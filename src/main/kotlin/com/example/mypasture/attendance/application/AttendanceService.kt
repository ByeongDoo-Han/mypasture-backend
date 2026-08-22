package com.example.mypasture.attendance.application

import com.example.mypasture.attendance.domain.AttendanceEntity
import com.example.mypasture.attendance.infrastructure.AttendanceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.*
import java.util.UUID

/** QT QR 참여를 멱등 출석으로 기록하고 연속 출석 일수를 계산합니다. */
@Service
class AttendanceService(private val repository: AttendanceRepository, private val clock: Clock = Clock.systemUTC()) {
    @Transactional
    fun recordQuietTime(userId: UUID, pastureId: UUID?, sessionId: UUID, timezone: String = "Asia/Seoul"): AttendanceEntity {
        repository.findByUserIdAndSessionId(userId, sessionId)?.let { return it }
        val now = Instant.now(clock)
        return repository.save(AttendanceEntity(userId = userId, pastureId = pastureId, sessionId = sessionId, attendanceDate = LocalDate.ofInstant(now, ZoneId.of(timezone)), checkedInAt = now))
    }

    @Transactional(readOnly = true)
    fun summary(userId: UUID, from: LocalDate, to: LocalDate): AttendanceSummary {
        val records = repository.findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(userId, from, to)
        val dates = records.map { it.attendanceDate }.distinct().sortedDescending()
        var streak = 0
        var cursor = dates.firstOrNull()
        while (cursor != null && dates.contains(cursor)) { streak++; cursor = cursor.minusDays(1) }
        return AttendanceSummary(records, streak)
    }
}
data class AttendanceSummary(val records: List<AttendanceEntity>, val currentStreak: Int)
