package com.example.mypasture.auth.infrastructure

import com.example.mypasture.auth.domain.AdminEmailLoginStateEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** 사용자 행 잠금 아래에서만 변경하는 계정별 관리자 인증 상태 저장소입니다. */
interface AdminEmailLoginStateRepository : JpaRepository<AdminEmailLoginStateEntity, UUID> {
    fun findByChallengeId(challengeId: UUID): AdminEmailLoginStateEntity?
}
