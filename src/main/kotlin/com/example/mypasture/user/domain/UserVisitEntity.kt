package com.example.mypasture.user.domain

import jakarta.persistence.*
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 로그인 상태로 이용한 한국 날짜를 사용자당 하루 한 번 기록합니다. */
@Entity
@Table(name = "user_visits", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "visited_on"])])
class UserVisitEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "visited_on", nullable = false) val visitedOn: LocalDate,
    @Column(nullable = false) val createdAt: Instant = Instant.now(),
)
