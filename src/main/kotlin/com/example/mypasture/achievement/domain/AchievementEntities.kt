package com.example.mypasture.achievement.domain

import com.example.mypasture.user.domain.UserEntity
import jakarta.persistence.*
import org.hibernate.annotations.Check
import org.hibernate.annotations.ColumnDefault
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 한번 받은 배지를 원본 기록 삭제와 무관하게 보존하는 영속 원장입니다. */
@Entity
@Table(name = "user_achievement_awards", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "achievement_code"])])
@Check(constraints = "rule_version > 0")
class AchievementAwardEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) @OnDelete(action = OnDeleteAction.CASCADE) val user: UserEntity,
    @Column(name = "achievement_code", nullable = false, length = 48) val achievementCode: String,
    @Column(name = "awarded_at", nullable = false) val awardedAt: Instant,
    @Column(name = "rule_version", nullable = false) @ColumnDefault("1") val ruleVersion: Int = AchievementCatalog.RULE_VERSION,
)

/** 메모를 지운 뒤에도 최초 작성한 한국 날짜를 유지합니다. */
@Entity
@Table(name = "user_note_activity_days", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "recorded_on"])])
class NoteActivityDayEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) @OnDelete(action = OnDeleteAction.CASCADE) val user: UserEntity,
    @Column(name = "recorded_on", nullable = false) val recordedOn: LocalDate,
)

/** 본인이 획득한 업적만 최대 세 자리의 대표 배지로 연결합니다. */
@Entity
@Table(name = "user_achievement_featured", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "achievement_code"]), UniqueConstraint(columnNames = ["user_id", "position"])])
@Check(constraints = "position between 0 and 2")
class FeaturedAchievementEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false) @OnDelete(action = OnDeleteAction.CASCADE) val user: UserEntity,
    // 소유자+업적의 복합 FK는 Flyway에서 관리합니다. 비PK 복합 JPA 연관 매핑을 사용하지 않습니다.
    @Column(name = "achievement_code", nullable = false, length = 48) val achievementCode: String,
    @Column(nullable = false) val position: Int,
)
