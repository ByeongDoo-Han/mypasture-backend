package com.example.mypasture.attendance.infrastructure

import com.example.mypasture.attendance.domain.AttendanceEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

interface AttendanceRepository : JpaRepository<AttendanceEntity, UUID> {
    fun findByUserIdAndSessionId(userId: UUID, sessionId: UUID): AttendanceEntity?
    fun findAllByUserIdAndAttendanceDateBetweenOrderByAttendanceDateDesc(userId: UUID, from: LocalDate, to: LocalDate): List<AttendanceEntity>
}
