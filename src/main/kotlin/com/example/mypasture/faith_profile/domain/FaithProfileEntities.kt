package com.example.mypasture.faith_profile.domain

import com.example.mypasture.user.domain.UserEntity
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.OneToOne
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/** 테스트 버전의 배포 상태를 표현하는 enum입니다. */
enum class TestVersionStatus { DRAFT, PUBLISHED, ARCHIVED }

/** 사용자가 진행 중인 진단의 상태를 표현하는 enum입니다. */
enum class AssessmentStatus { IN_PROGRESS, COMPLETED }

/** 질문 응답 점수가 어느 쪽 성향에 더해지는지 표현하는 enum입니다. */
enum class DimensionSide { LEFT, RIGHT }

/** 유형별 추천 콘텐츠의 연결 목적을 표현하는 enum입니다. */
enum class RecommendationCategory { DAILY_WORD, PRAYER, MEDITATION, CHALLENGE, SMALL_GROUP }

/** 신앙 성향 테스트의 버전과 배포 상태를 관리하는 JPA entity입니다. */
@Entity
@Table(name = "faith_test_versions")
class FaithTestVersionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    val code: String,
    val version: Int,
    val title: String,
    @Enumerated(EnumType.STRING)
    val status: TestVersionStatus = TestVersionStatus.DRAFT,
    val publishedAt: Instant? = null,
    val createdAt: Instant = Instant.now(),
)

/** 말씀/기도 같은 한 축의 좌우 성향 정의를 저장하는 JPA entity입니다. */
@Entity
@Table(name = "faith_dimensions")
class FaithDimensionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val testVersion: FaithTestVersionEntity,
    val code: String,
    val leftCode: String,
    val leftName: String,
    val rightCode: String,
    val rightName: String,
    val sortOrder: Int,
)

/** 사용자가 응답할 테스트 문항과 채점 방향을 저장하는 JPA entity입니다. */
@Entity
@Table(name = "faith_questions")
class FaithQuestionEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val testVersion: FaithTestVersionEntity,
    @ManyToOne(fetch = FetchType.LAZY)
    val dimension: FaithDimensionEntity,
    @Column(columnDefinition = "text")
    val questionText: String,
    @Enumerated(EnumType.STRING)
    val targetSide: DimensionSide,
    val reverseScored: Boolean = false,
    val sortOrder: Int,
    val active: Boolean = true,
)

/** 사용자가 테스트를 시작하고 완료하기까지의 세션을 저장하는 JPA entity입니다. */
@Entity
@Table(name = "faith_assessments")
class FaithAssessmentEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val user: UserEntity? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    val testVersion: FaithTestVersionEntity,
    @Enumerated(EnumType.STRING)
    var status: AssessmentStatus = AssessmentStatus.IN_PROGRESS,
    val startedAt: Instant = Instant.now(),
    var completedAt: Instant? = null,
)

/** 사용자가 각 문항에 남긴 1~5점 Likert 응답을 저장하는 JPA entity입니다. */
@Entity
@Table(name = "faith_answers")
class FaithAnswerEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val assessment: FaithAssessmentEntity,
    @ManyToOne(fetch = FetchType.LAZY)
    val question: FaithQuestionEntity,
    val score: Int,
    val createdAt: Instant = Instant.now(),
)

/** 진단 완료 시 계산된 최종 유형과 설명 스냅샷을 저장하는 JPA entity입니다. */
@Entity
@Table(name = "faith_results")
class FaithResultEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @OneToOne(fetch = FetchType.LAZY)
    val assessment: FaithAssessmentEntity,
    val typeCode: String,
    val typeName: String,
    @Column(columnDefinition = "text")
    val summary: String,
    val createdAt: Instant = Instant.now(),
)

/** 최종 결과에서 각 축의 좌우 점수와 선택된 성향을 저장하는 JPA entity입니다. */
@Entity
@Table(name = "faith_dimension_scores")
class FaithDimensionScoreEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val result: FaithResultEntity,
    val dimensionCode: String,
    val leftCode: String,
    val rightCode: String,
    val leftScore: Int,
    val rightScore: Int,
    val selectedCode: String,
    val confidence: Int,
)

/** 유형 코드별 이름, 강점, 주의점, 성장 가이드를 관리하는 JPA entity입니다. */
@Entity
@Table(name = "faith_type_profiles")
class FaithTypeProfileEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val testVersion: FaithTestVersionEntity,
    val typeCode: String,
    val typeName: String,
    @Column(columnDefinition = "text")
    val summary: String,
    @Column(columnDefinition = "text")
    val strengths: String,
    @Column(columnDefinition = "text")
    val cautions: String,
    @Column(columnDefinition = "text")
    val growthGuide: String,
)

/** 유형별 추천 루틴, 기도, 묵상, 챌린지 연결 콘텐츠를 저장하는 JPA entity입니다. */
@Entity
@Table(name = "faith_recommendations")
class FaithRecommendationEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val typeProfile: FaithTypeProfileEntity,
    @Enumerated(EnumType.STRING)
    val category: RecommendationCategory,
    val title: String,
    @Column(columnDefinition = "text")
    val content: String,
    val targetFeature: String? = null,
    val sortOrder: Int,
)
