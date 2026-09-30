package com.example.mypasture.user.infrastructure

import com.example.mypasture.user.domain.UserVisitEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

/** 사용자와 날짜를 조건으로 본인의 방문 기록만 조회합니다. */
interface UserVisitRepository : JpaRepository<UserVisitEntity, UUID> {
    fun existsByUserIdAndVisitedOn(userId: UUID, visitedOn: LocalDate): Boolean
    fun findAllByUserIdAndVisitedOnBetween(userId: UUID, from: LocalDate, to: LocalDate): List<UserVisitEntity>
}
