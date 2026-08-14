package com.example.mypasture.faith_profile.infrastructure

import com.example.mypasture.faith_profile.domain.FaithAnswerEntity
import com.example.mypasture.faith_profile.domain.FaithAssessmentEntity
import com.example.mypasture.faith_profile.domain.FaithDimensionEntity
import com.example.mypasture.faith_profile.domain.FaithDimensionScoreEntity
import com.example.mypasture.faith_profile.domain.FaithQuestionEntity
import com.example.mypasture.faith_profile.domain.FaithRecommendationEntity
import com.example.mypasture.faith_profile.domain.FaithResultEntity
import com.example.mypasture.faith_profile.domain.FaithTestVersionEntity
import com.example.mypasture.faith_profile.domain.FaithTypeProfileEntity
import com.example.mypasture.faith_profile.domain.TestVersionStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

/** 신앙 성향 테스트 버전을 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithTestVersionRepository : JpaRepository<FaithTestVersionEntity, UUID> {
    fun findFirstByCodeAndStatusOrderByVersionDesc(code: String, status: TestVersionStatus): FaithTestVersionEntity?

    fun existsByCodeAndVersion(code: String, version: Int): Boolean
}

/** 테스트 축 정의를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithDimensionRepository : JpaRepository<FaithDimensionEntity, UUID> {
    fun findByTestVersionOrderBySortOrderAsc(testVersion: FaithTestVersionEntity): List<FaithDimensionEntity>
}

/** 테스트 문항을 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithQuestionRepository : JpaRepository<FaithQuestionEntity, UUID> {
    fun findByTestVersionAndActiveTrueOrderBySortOrderAsc(testVersion: FaithTestVersionEntity): List<FaithQuestionEntity>
}

/** 사용자의 진단 세션을 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithAssessmentRepository : JpaRepository<FaithAssessmentEntity, UUID>

/** 문항별 사용자 응답을 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithAnswerRepository : JpaRepository<FaithAnswerEntity, UUID> {
    fun findByAssessment(assessment: FaithAssessmentEntity): List<FaithAnswerEntity>

    fun findByAssessmentAndQuestion(assessment: FaithAssessmentEntity, question: FaithQuestionEntity): FaithAnswerEntity?

    fun countByAssessment(assessment: FaithAssessmentEntity): Long
}

/** 최종 진단 결과를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithResultRepository : JpaRepository<FaithResultEntity, UUID> {
    fun findByAssessment(assessment: FaithAssessmentEntity): FaithResultEntity?
}

/** 축별 점수 결과를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithDimensionScoreRepository : JpaRepository<FaithDimensionScoreEntity, UUID> {
    fun findByResult(result: FaithResultEntity): List<FaithDimensionScoreEntity>
}

/** 유형 코드별 설명 프로필을 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithTypeProfileRepository : JpaRepository<FaithTypeProfileEntity, UUID> {
    fun findByTestVersionAndTypeCode(testVersion: FaithTestVersionEntity, typeCode: String): FaithTypeProfileEntity?
}

/** 유형별 추천 콘텐츠를 조회하고 저장하는 JPA repository입니다. */
@Repository
interface FaithRecommendationRepository : JpaRepository<FaithRecommendationEntity, UUID> {
    fun findByTypeProfileOrderBySortOrderAsc(typeProfile: FaithTypeProfileEntity): List<FaithRecommendationEntity>
}
