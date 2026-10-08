package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.daily_word.domain.DailyWordRotationLockEntity
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query

/** 배정·관리자 변경·AI 저장이 공유하는 DB 잠금을 트랜잭션 안에서 획득합니다. */
interface DailyWordRotationLockRepository : JpaRepository<DailyWordRotationLockEntity, Int> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select guard from DailyWordRotationLockEntity guard where guard.id = 1")
    fun lock(): DailyWordRotationLockEntity?
}
