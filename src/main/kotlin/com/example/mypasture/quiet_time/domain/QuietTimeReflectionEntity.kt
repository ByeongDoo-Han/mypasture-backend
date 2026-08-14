package com.example.mypasture.quiet_time.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

/** 현장 QT 참여자가 작성한 묵상과 목장 공유 여부를 저장합니다. */
@Entity @Table(name = "quiet_time_reflections", uniqueConstraints = [UniqueConstraint(columnNames = ["session_id", "user_id"])])
class QuietTimeReflectionEntity(@Id val id: UUID = UUID.randomUUID(), @ManyToOne(fetch = FetchType.LAZY) val session: QuietTimeSessionEntity, @Column(name = "user_id") val userId: UUID, @Column(columnDefinition = "text") var content: String, var sharedWithPasture: Boolean = false, val createdAt: Instant = Instant.now(), var updatedAt: Instant = Instant.now()) {
    fun update(content: String, shared: Boolean, now: Instant) { this.content = content; this.sharedWithPasture = shared; this.updatedAt = now }
}
