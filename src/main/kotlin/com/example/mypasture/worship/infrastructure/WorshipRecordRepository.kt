package com.example.mypasture.worship.infrastructure

import com.example.mypasture.worship.domain.WorshipRecordEntity
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** 사용자 소유 예배 기록만 페이지 또는 ID로 조회합니다. */
interface WorshipRecordRepository : JpaRepository<WorshipRecordEntity, UUID> {
    fun findAllByUserId(userId: UUID, pageable: Pageable): Page<WorshipRecordEntity>
    fun findByIdAndUserId(id: UUID, userId: UUID): WorshipRecordEntity?
}
