package com.example.mypasture.achievement.infrastructure

import com.example.mypasture.achievement.domain.AchievementAwardEntity
import com.example.mypasture.achievement.domain.FeaturedAchievementEntity
import com.example.mypasture.achievement.domain.NoteActivityDayEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

/** 사용자별 획득 원장을 일괄 조회해 중복 수여를 방지합니다. */
interface AchievementAwardRepository : JpaRepository<AchievementAwardEntity, UUID> {
    fun findAllByUser_Id(userId: UUID): List<AchievementAwardEntity>
}

/** 삭제와 무관하게 보존한 메모 작성일을 조회합니다. */
interface NoteActivityDayRepository : JpaRepository<NoteActivityDayEntity, UUID> {
    fun findAllByUser_Id(userId: UUID): List<NoteActivityDayEntity>
}

/** 사용자가 정한 대표 배지 순서를 조회하고 교체합니다. */
interface FeaturedAchievementRepository : JpaRepository<FeaturedAchievementEntity, UUID> {
    fun findAllByUser_IdOrderByPosition(userId: UUID): List<FeaturedAchievementEntity>
}
