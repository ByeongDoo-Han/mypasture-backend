package com.example.mypasture.faith_profile.application

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.faith_profile.domain.AssessmentStatus
import com.example.mypasture.faith_profile.domain.DimensionSide
import com.example.mypasture.faith_profile.domain.FaithAnswerEntity
import com.example.mypasture.faith_profile.domain.FaithAssessmentEntity
import com.example.mypasture.faith_profile.domain.FaithDimensionEntity
import com.example.mypasture.faith_profile.domain.FaithDimensionScoreEntity
import com.example.mypasture.faith_profile.domain.FaithQuestionEntity
import com.example.mypasture.faith_profile.domain.FaithRecommendationEntity
import com.example.mypasture.faith_profile.domain.FaithResultEntity
import com.example.mypasture.faith_profile.domain.FaithTestVersionEntity
import com.example.mypasture.faith_profile.domain.FaithTypeProfileEntity
import com.example.mypasture.faith_profile.domain.RecommendationCategory
import com.example.mypasture.faith_profile.domain.TestVersionStatus
import com.example.mypasture.faith_profile.infrastructure.FaithAnswerRepository
import com.example.mypasture.faith_profile.infrastructure.FaithAssessmentRepository
import com.example.mypasture.faith_profile.infrastructure.FaithDimensionRepository
import com.example.mypasture.faith_profile.infrastructure.FaithDimensionScoreRepository
import com.example.mypasture.faith_profile.infrastructure.FaithQuestionRepository
import com.example.mypasture.faith_profile.infrastructure.FaithRecommendationRepository
import com.example.mypasture.faith_profile.infrastructure.FaithResultRepository
import com.example.mypasture.faith_profile.infrastructure.FaithTestVersionRepository
import com.example.mypasture.faith_profile.infrastructure.FaithTypeProfileRepository
import java.time.Instant
import java.util.UUID
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 신앙 성향 테스트 조회, 시작, 응답, 결과 계산 use case를 처리하는 application service입니다. */
@Service
class FaithProfileService(
    private val userRepository: UserRepository,
    private val testVersionRepository: FaithTestVersionRepository,
    private val dimensionRepository: FaithDimensionRepository,
    private val questionRepository: FaithQuestionRepository,
    private val assessmentRepository: FaithAssessmentRepository,
    private val answerRepository: FaithAnswerRepository,
    private val resultRepository: FaithResultRepository,
    private val dimensionScoreRepository: FaithDimensionScoreRepository,
    private val typeProfileRepository: FaithTypeProfileRepository,
    private val recommendationRepository: FaithRecommendationRepository,
) {
    @Transactional
    fun getCurrentTest(): FaithTestDefinition {
        val testVersion = ensureDefaultTestVersion()
        return FaithTestDefinition(
            testVersion = testVersion,
            dimensions = dimensionRepository.findByTestVersionOrderBySortOrderAsc(testVersion),
            questions = questionRepository.findByTestVersionAndActiveTrueOrderBySortOrderAsc(testVersion),
        )
    }

    @Transactional
    fun startAssessment(userId: UUID?): FaithAssessmentEntity {
        val testVersion = ensureDefaultTestVersion()
        val user = userId?.let { userRepository.findById(it).orElseThrow { FaithProfileNotFoundException("User was not found: $it") } }
        return assessmentRepository.save(FaithAssessmentEntity(user = user, testVersion = testVersion))
    }

    @Transactional
    fun answerQuestion(
        assessmentId: UUID,
        questionId: UUID,
        score: Int,
    ): FaithAnswerEntity {
        require(score in 1..5) { "Score must be between 1 and 5." }
        val assessment = getAssessment(assessmentId)
        if (assessment.status == AssessmentStatus.COMPLETED) throw FaithAssessmentAlreadyCompletedException()
        val question = questionRepository.findById(questionId).orElseThrow { FaithProfileNotFoundException("Question was not found: $questionId") }
        if (question.testVersion.id != assessment.testVersion.id) throw FaithProfileBadRequestException("Question does not belong to assessment test version.")
        if (answerRepository.findByAssessmentAndQuestion(assessment, question) != null) throw FaithAnswerAlreadyExistsException()
        return answerRepository.save(FaithAnswerEntity(assessment = assessment, question = question, score = score))
    }

    @Transactional
    fun completeAssessment(assessmentId: UUID): FaithResultView {
        val assessment = getAssessment(assessmentId)
        resultRepository.findByAssessment(assessment)?.let { return toResultView(it) }
        if (assessment.status == AssessmentStatus.COMPLETED) throw FaithAssessmentAlreadyCompletedException()

        val questions = questionRepository.findByTestVersionAndActiveTrueOrderBySortOrderAsc(assessment.testVersion)
        val answers = answerRepository.findByAssessment(assessment)
        if (answers.size != questions.size) {
            throw FaithProfileBadRequestException("All questions must be answered before completing assessment.")
        }

        val scoreRows = calculateScores(answers)
        val typeCode = scoreRows.joinToString("") { it.selectedCode }
        val typeProfile = typeProfileRepository.findByTestVersionAndTypeCode(assessment.testVersion, typeCode)
            ?: throw FaithProfileNotFoundException("Faith type profile was not found: $typeCode")
        val result = resultRepository.save(
            FaithResultEntity(
                assessment = assessment,
                typeCode = typeProfile.typeCode,
                typeName = typeProfile.typeName,
                summary = typeProfile.summary,
            ),
        )

        dimensionScoreRepository.saveAll(
            scoreRows.map {
                FaithDimensionScoreEntity(
                    result = result,
                    dimensionCode = it.dimensionCode,
                    leftCode = it.leftCode,
                    rightCode = it.rightCode,
                    leftScore = it.leftScore,
                    rightScore = it.rightScore,
                    selectedCode = it.selectedCode,
                    confidence = it.confidence,
                )
            },
        )
        assessment.status = AssessmentStatus.COMPLETED
        assessment.completedAt = Instant.now()
        return toResultView(result)
    }

    @Transactional(readOnly = true)
    fun getResult(resultId: UUID): FaithResultView {
        val result = resultRepository.findById(resultId).orElseThrow { FaithProfileNotFoundException("Faith result was not found: $resultId") }
        return toResultView(result)
    }

    private fun calculateScores(answers: List<FaithAnswerEntity>): List<DimensionScoreDraft> =
        answers
            .groupBy { it.question.dimension }
            .toSortedMap(compareBy { it.sortOrder })
            .map { (dimension, dimensionAnswers) ->
                var leftScore = 0
                var rightScore = 0
                dimensionAnswers.forEach { answer ->
                    val normalizedScore = if (answer.question.reverseScored) 6 - answer.score else answer.score
                    val oppositeScore = 6 - normalizedScore
                    if (answer.question.targetSide == DimensionSide.LEFT) {
                        leftScore += normalizedScore
                        rightScore += oppositeScore
                    } else {
                        rightScore += normalizedScore
                        leftScore += oppositeScore
                    }
                }
                DimensionScoreDraft(
                    dimensionCode = dimension.code,
                    leftCode = dimension.leftCode,
                    rightCode = dimension.rightCode,
                    leftScore = leftScore,
                    rightScore = rightScore,
                    selectedCode = if (leftScore >= rightScore) dimension.leftCode else dimension.rightCode,
                    confidence = kotlin.math.abs(leftScore - rightScore),
                )
            }

    private fun toResultView(result: FaithResultEntity): FaithResultView {
        val typeProfile = typeProfileRepository.findByTestVersionAndTypeCode(result.assessment.testVersion, result.typeCode)
            ?: throw FaithProfileNotFoundException("Faith type profile was not found: ${result.typeCode}")
        return FaithResultView(
            result = result,
            dimensionScores = dimensionScoreRepository.findByResult(result),
            typeProfile = typeProfile,
            recommendations = recommendationRepository.findByTypeProfileOrderBySortOrderAsc(typeProfile),
        )
    }

    private fun getAssessment(assessmentId: UUID): FaithAssessmentEntity =
        assessmentRepository.findById(assessmentId).orElseThrow { FaithProfileNotFoundException("Assessment was not found: $assessmentId") }

    private fun ensureDefaultTestVersion(): FaithTestVersionEntity {
        testVersionRepository.findFirstByCodeAndStatusOrderByVersionDesc(DEFAULT_TEST_CODE, TestVersionStatus.PUBLISHED)?.let { return it }
        if (testVersionRepository.existsByCodeAndVersion(DEFAULT_TEST_CODE, DEFAULT_TEST_VERSION)) {
            return testVersionRepository.findFirstByCodeAndStatusOrderByVersionDesc(DEFAULT_TEST_CODE, TestVersionStatus.PUBLISHED)
                ?: throw FaithProfileNotFoundException("Published faith test was not found.")
        }

        val testVersion = testVersionRepository.save(
            FaithTestVersionEntity(
                code = DEFAULT_TEST_CODE,
                version = DEFAULT_TEST_VERSION,
                title = "믿음 리듬 테스트",
                status = TestVersionStatus.PUBLISHED,
                publishedAt = Instant.now(),
            ),
        )
        val dimensions = seedDimensions(testVersion)
        seedQuestions(testVersion, dimensions)
        seedTypeProfiles(testVersion)
        return testVersion
    }

    private fun seedDimensions(testVersion: FaithTestVersionEntity): Map<String, FaithDimensionEntity> =
        dimensionRepository.saveAll(
            listOf(
                FaithDimensionEntity(testVersion = testVersion, code = "WORD_PRAYER", leftCode = "W", leftName = "말씀형", rightCode = "P", rightName = "기도형", sortOrder = 1),
                FaithDimensionEntity(testVersion = testVersion, code = "MEDITATION_ACTION", leftCode = "M", leftName = "묵상형", rightCode = "A", rightName = "실천형", sortOrder = 2),
                FaithDimensionEntity(testVersion = testVersion, code = "COMMUNITY_SOLITUDE", leftCode = "C", leftName = "공동체형", rightCode = "S", rightName = "개인형", sortOrder = 3),
                FaithDimensionEntity(testVersion = testVersion, code = "GRACE_DISCIPLINE", leftCode = "G", leftName = "위로형", rightCode = "D", rightName = "도전형", sortOrder = 4),
            ),
        ).associateBy { it.code }

    private fun seedQuestions(
        testVersion: FaithTestVersionEntity,
        dimensions: Map<String, FaithDimensionEntity>,
    ) {
        questionRepository.saveAll(
            listOf(
                question(testVersion, dimensions, "WORD_PRAYER", "나는 말씀을 읽고 뜻을 이해할 때 신앙이 더 선명해진다.", DimensionSide.LEFT, 1),
                question(testVersion, dimensions, "WORD_PRAYER", "나는 조용히 기도하며 마음을 털어놓을 때 하나님과 가까워진다.", DimensionSide.RIGHT, 2),
                question(testVersion, dimensions, "MEDITATION_ACTION", "나는 묵상한 내용을 오래 곱씹는 편이다.", DimensionSide.LEFT, 3),
                question(testVersion, dimensions, "MEDITATION_ACTION", "나는 배운 말씀을 바로 실천할 때 은혜가 깊어진다.", DimensionSide.RIGHT, 4),
                question(testVersion, dimensions, "COMMUNITY_SOLITUDE", "나는 공동체 나눔을 통해 신앙의 힘을 얻는다.", DimensionSide.LEFT, 5),
                question(testVersion, dimensions, "COMMUNITY_SOLITUDE", "나는 혼자 하나님과 보내는 시간이 꼭 필요하다.", DimensionSide.RIGHT, 6),
                question(testVersion, dimensions, "GRACE_DISCIPLINE", "나는 위로와 회복의 말씀에 마음이 많이 열린다.", DimensionSide.LEFT, 7),
                question(testVersion, dimensions, "GRACE_DISCIPLINE", "나는 결단과 도전의 말씀을 들을 때 다시 일어난다.", DimensionSide.RIGHT, 8),
            ),
        )
    }

    private fun question(
        testVersion: FaithTestVersionEntity,
        dimensions: Map<String, FaithDimensionEntity>,
        dimensionCode: String,
        text: String,
        targetSide: DimensionSide,
        sortOrder: Int,
    ): FaithQuestionEntity =
        FaithQuestionEntity(
            testVersion = testVersion,
            dimension = dimensions.getValue(dimensionCode),
            questionText = text,
            targetSide = targetSide,
            sortOrder = sortOrder,
        )

    private fun seedTypeProfiles(testVersion: FaithTestVersionEntity) {
        defaultTypeNames.forEach { (typeCode, typeName) ->
            val typeProfile = typeProfileRepository.save(
                FaithTypeProfileEntity(
                    testVersion = testVersion,
                    typeCode = typeCode,
                    typeName = typeName,
                    summary = "$typeName 유형은 하나님을 만나는 자신만의 리듬이 분명한 신앙 성향입니다.",
                    strengths = "자신에게 맞는 방식으로 말씀과 기도 루틴을 꾸준히 세우기 좋습니다.",
                    cautions = "한쪽 성향만 정답처럼 붙잡기보다 반대편 리듬도 조금씩 연습하면 균형이 생깁니다.",
                    growthGuide = "오늘 하루 한 가지 말씀, 한 문장 기도, 한 가지 실천을 작게 연결해 보세요.",
                ),
            )
            recommendationRepository.saveAll(defaultRecommendations(typeProfile))
        }
    }

    private fun defaultRecommendations(typeProfile: FaithTypeProfileEntity): List<FaithRecommendationEntity> =
        listOf(
            FaithRecommendationEntity(typeProfile = typeProfile, category = RecommendationCategory.DAILY_WORD, title = "오늘의 말씀 루틴", content = "하루 한 장을 천천히 읽고 마음에 남는 한 절을 저장해 보세요.", targetFeature = "BIBLE_READING", sortOrder = 1),
            FaithRecommendationEntity(typeProfile = typeProfile, category = RecommendationCategory.PRAYER, title = "한 문장 기도", content = "오늘의 감정을 하나님께 한 문장으로 고백해 보세요.", targetFeature = "PRAYER_JOURNAL", sortOrder = 2),
            FaithRecommendationEntity(typeProfile = typeProfile, category = RecommendationCategory.CHALLENGE, title = "작은 실천 챌린지", content = "묵상한 말씀에서 오늘 바로 할 수 있는 작은 행동 하나를 정해 보세요.", targetFeature = "CHALLENGE", sortOrder = 3),
        )

    companion object {
        private const val DEFAULT_TEST_CODE = "FAITH_RHYTHM"
        private const val DEFAULT_TEST_VERSION = 1

        private val defaultTypeNames =
            mapOf(
                "WMCG" to "조용한 말씀 순례자",
                "WMCD" to "말씀으로 공동체를 세우는 훈련가",
                "WMSG" to "깊은 말씀 묵상가",
                "WMSD" to "말씀 안에서 단단해지는 사람",
                "WACG" to "삶으로 전하는 격려자",
                "WACD" to "말씀을 행동으로 옮기는 리더",
                "WASG" to "일상 속 말씀 실천가",
                "WASD" to "결단하는 말씀 실행가",
                "PMCG" to "함께 기도하는 위로자",
                "PMCD" to "공동체를 품는 기도 리더",
                "PMSG" to "은혜를 머금은 기도자",
                "PMSD" to "침묵 속에서 단단해지는 사람",
                "PACG" to "기도로 돌보는 격려자",
                "PACD" to "기도로 움직이는 사명가",
                "PASG" to "일상에서 기도하는 동행자",
                "PASD" to "기도와 결단의 실행가",
            )
    }
}

/** 현재 테스트 정의를 controller에 전달하기 위한 application model입니다. */
data class FaithTestDefinition(
    val testVersion: FaithTestVersionEntity,
    val dimensions: List<FaithDimensionEntity>,
    val questions: List<FaithQuestionEntity>,
)

/** 최종 결과 화면에 필요한 결과, 축별 점수, 유형 설명, 추천 콘텐츠 묶음입니다. */
data class FaithResultView(
    val result: FaithResultEntity,
    val dimensionScores: List<FaithDimensionScoreEntity>,
    val typeProfile: FaithTypeProfileEntity,
    val recommendations: List<FaithRecommendationEntity>,
)

/** 축별 계산 결과를 영속화하기 전 임시로 담는 application model입니다. */
private data class DimensionScoreDraft(
    val dimensionCode: String,
    val leftCode: String,
    val rightCode: String,
    val leftScore: Int,
    val rightScore: Int,
    val selectedCode: String,
    val confidence: Int,
)

/** 신앙 성향 테스트 리소스를 찾을 수 없을 때 발생하는 예외입니다. */
class FaithProfileNotFoundException(message: String) : RuntimeException(message)

/** 완료된 진단을 다시 수정하거나 완료하려고 할 때 발생하는 예외입니다. */
class FaithAssessmentAlreadyCompletedException : RuntimeException("Faith assessment is already completed")

/** 같은 문항에 중복 답변을 제출했을 때 발생하는 예외입니다. */
class FaithAnswerAlreadyExistsException : RuntimeException("Faith answer already exists")

/** 신앙 성향 테스트 요청의 비즈니스 규칙이 맞지 않을 때 발생하는 예외입니다. */
class FaithProfileBadRequestException(message: String) : RuntimeException(message)
