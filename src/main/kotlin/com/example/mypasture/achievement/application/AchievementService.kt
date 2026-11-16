package com.example.mypasture.achievement.application

import com.example.mypasture.achievement.domain.*
import com.example.mypasture.achievement.infrastructure.*
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.UserEntity
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 원본 행위와 같은 트랜잭션에서 업적을 수여하고 대표 배지와 소급 평가를 관리합니다. */
@Service
class AchievementService(
    private val users: UserRepository,
    private val awards: AchievementAwardRepository,
    private val noteDays: NoteActivityDayRepository,
    private val featured: FeaturedAchievementRepository,
    private val evidence: AchievementEvidenceReader,
    private val entityManager: EntityManager,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun summary(userId: UUID): AchievementSummary = result(userId, evidence.read(userId), emptyList())

    @Transactional
    fun reconcile(userId: UUID): AchievementSummary {
        val user = lockUser(userId)
        preserveNoteDays(user, evidence.existingNoteDays(userId))
        entityManager.flush()
        val snapshot = evidence.read(userId)
        val newlyAwarded = award(user, snapshot)
        return result(userId, snapshot, newlyAwarded)
    }

    @Transactional
    fun setFeatured(userId: UUID, codes: List<String>): AchievementSummary {
        val user = lockUser(userId)
        require(codes.size <= 3 && codes.distinct().size == codes.size) { "Choose up to three different achievements" }
        val owned = awards.findAllByUser_Id(userId).associateBy { it.achievementCode }
        require(codes.all { it in owned && AchievementCatalog.definitions.any { definition -> definition.code == it } }) { "Only earned achievements can be featured" }
        val previous = featured.findAllByUser_IdOrderByPosition(userId)
        if (previous.map { it.achievementCode } != codes) {
            featured.deleteAll(previous)
            featured.flush()
            featured.saveAll(codes.mapIndexed { position, code -> FeaturedAchievementEntity(user = user, achievementCode = code, position = position) })
        }
        return result(userId, evidence.read(userId), emptyList())
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun lockUser(userId: UUID): UserEntity = checkNotNull(users.findActiveByIdForUpdate(userId)) { "Active user is required" }

    /** 원본 변경을 flush한 뒤 같은 사용자 잠금 안에서 판정합니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun recordProgress(userId: UUID) {
        val user = lockUser(userId)
        entityManager.flush()
        award(user, evidence.read(userId))
    }

    /** 삭제 전에도 기존 날짜를 보존하므로 메모 삭제로 누적 작성일을 잃지 않습니다. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun preserveNotesAndRecord(userId: UUID, createdOn: LocalDate? = null) {
        val user = lockUser(userId)
        entityManager.flush()
        preserveNoteDays(user, evidence.existingNoteDays(userId) + listOfNotNull(createdOn))
        entityManager.flush()
        award(user, evidence.read(userId))
    }

    private fun preserveNoteDays(user: UserEntity, dates: Set<LocalDate>) {
        val existing = noteDays.findAllByUser_Id(user.id).map { it.recordedOn }.toSet()
        noteDays.saveAll((dates - existing).map { NoteActivityDayEntity(user = user, recordedOn = it) })
    }

    private fun award(user: UserEntity, snapshot: AchievementEvidence): List<String> {
        val owned = awards.findAllByUser_Id(user.id).map { it.achievementCode }.toSet()
        val newlyAwarded = AchievementCatalog.definitions.filter { it.code !in owned && snapshot.progress(it.category) >= it.target }
        val now = Instant.now(clock)
        awards.saveAll(newlyAwarded.map { AchievementAwardEntity(user = user, achievementCode = it.code, awardedAt = now) })
        return newlyAwarded.map { it.code }
    }

    private fun result(userId: UUID, snapshot: AchievementEvidence, newlyAwarded: List<String>): AchievementSummary {
        val owned = awards.findAllByUser_Id(userId).associateBy { it.achievementCode }
        return AchievementSummary(
            userId, AchievementCatalog.definitions.size, AchievementCatalog.definitions.count { it.code in owned },
            featured.findAllByUser_IdOrderByPosition(userId).map { it.achievementCode }, newlyAwarded,
            AchievementCatalog.definitions.map { definition ->
                val awardedAt = owned[definition.code]?.awardedAt
                AchievementItem(definition.code, definition.category, definition.title, definition.description, definition.target,
                    if (awardedAt != null) definition.target else snapshot.progress(definition.category).coerceIn(0, definition.target), definition.unit, awardedAt)
            },
        )
    }
}

/** 업적의 서버 판정과 대표 배지 순서를 제공하는 응답입니다. */
data class AchievementSummary(val userId: UUID, val totalCount: Int, val earnedCount: Int, val featuredCodes: List<String>, val newlyAwardedCodes: List<String>, val items: List<AchievementItem>)

/** 획득 후 목표를 유지하는 업적 하나의 공개 진행 정보입니다. */
data class AchievementItem(val code: String, val category: AchievementCategory, val title: String, val description: String, val target: Int, val progress: Int, val unit: String, val awardedAt: Instant?)
