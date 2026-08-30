package com.example.mypasture.coupon.application

import com.example.mypasture.coupon.domain.EntitlementType
import com.example.mypasture.coupon.domain.UserEntitlementEntity
import com.example.mypasture.coupon.infrastructure.UserEntitlementRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.UUID

/** 현재 시각에 유효한 기능 권한만 조회해 구독과 체험 권한의 수명주기를 분리합니다. */
@Service
class UserEntitlementService(private val repository: UserEntitlementRepository) {
    fun activePremiumAiTrial(userId: UUID, now: Instant = Instant.now()): UserEntitlementEntity? =
        repository.findActive(userId, EntitlementType.PREMIUM_AI_TRIAL, now, PageRequest.of(0, 1)).firstOrNull()

    fun current(userId: UUID, now: Instant = Instant.now()): List<EntitlementView> =
        listOfNotNull(activePremiumAiTrial(userId, now)?.toView())
}

internal fun UserEntitlementEntity.toView() = EntitlementView(id, entitlementType, startsAt, expiresAt, aiDailyLimit)
