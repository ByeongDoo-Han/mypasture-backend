package com.example.mypasture.attendance.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class AttendanceSource { QUIET_TIME_QR }

/** 인증 사용자가 현장 QT QR로 참여한 사실을 세션당 한 번 저장합니다. */
@Entity @Table(name = "pasture_attendances", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "session_id"])])
class AttendanceEntity(@Id val id: UUID = UUID.randomUUID(), @Column(name = "user_id") val userId: UUID, @Column(name = "pasture_id") val pastureId: UUID?, @Column(name = "session_id") val sessionId: UUID, val attendanceDate: LocalDate, val checkedInAt: Instant = Instant.now(), @Enumerated(EnumType.STRING) val source: AttendanceSource = AttendanceSource.QUIET_TIME_QR)
