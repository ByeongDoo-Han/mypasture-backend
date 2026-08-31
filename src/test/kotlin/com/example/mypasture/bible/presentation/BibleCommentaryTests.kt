package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.application.BibleCommentaryService
import com.example.mypasture.bible.application.BibleCommentaryEvaluation
import com.example.mypasture.bible.application.BibleCommentaryDraftEdit
import com.example.mypasture.bible.application.BibleCommentaryEvidenceService
import com.example.mypasture.bible.application.BibleCommentaryBatchService
import com.example.mypasture.bible.application.CreateBibleCommentaryEvidenceCommand
import com.example.mypasture.bible.application.GeneratedBibleCommentary
import com.example.mypasture.bible.application.GeneratedCommentaryReference
import com.example.mypasture.bible.application.GeneratedCommentarySection
import com.example.mypasture.bible.infrastructure.BibleChapterCommentaryRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryGenerationJobRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryEvidenceRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryReviewEventRepository
import com.example.mypasture.bible.domain.BibleCommentaryEvaluationStatus
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceStatus
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceType
import com.example.mypasture.bible.domain.BibleCommentaryStatus
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserRole
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** 장별 해설의 생성 큐, 검증, 관리자 게시와 공개 조회 흐름을 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true"])
class BibleCommentaryTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val commentaryService: BibleCommentaryService,
    @Autowired private val commentaryRepository: BibleChapterCommentaryRepository,
    @Autowired private val jobRepository: BibleCommentaryGenerationJobRepository,
    @Autowired private val evidenceService: BibleCommentaryEvidenceService,
    @Autowired private val evidenceRepository: BibleCommentaryEvidenceRepository,
    @Autowired private val reviewEventRepository: BibleCommentaryReviewEventRepository,
    @Autowired private val batchService: BibleCommentaryBatchService,
) {
    private val adminId = UUID.randomUUID()

    @BeforeEach
    fun cleanUp() {
        reviewEventRepository.deleteAll()
        commentaryRepository.deleteAll()
        jobRepository.deleteAll()
        evidenceRepository.deleteAll()
    }

    @Test
    fun `published commentary is returned without generating at request time`() {
        val draft = createDraft()
        commentaryService.publish(draft.id, adminId, 92, "본문과 배경 설명을 검수했습니다.")

        mockMvc.get("/api/v1/bibles/KOR1910/books/GEN/chapters/1/commentary")
            .andExpect {
                status { isOk() }
                jsonPath("$.bookCode") { value("GEN") }
                jsonPath("$.chapter") { value(1) }
                jsonPath("$.revision") { value(1) }
                jsonPath("$.sections.length()") { value(2) }
                jsonPath("$.sections[0].startVerse") { value(1) }
                jsonPath("$.crossReferences[0].verse") { value(1) }
                jsonPath("$.summary") { exists() }
            }
    }

    @Test
    fun `unpublished commentary returns not found`() {
        mockMvc.get("/api/v1/bibles/KOR1910/books/GEN/chapters/2/commentary")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("BIBLE_COMMENTARY_NOT_FOUND") }
            }
    }

    @Test
    fun `only admin can queue commentary generation`() {
        mockMvc.post("/api/v1/admin/bible-commentaries/generation-jobs") {
            with(user("member").roles("USER"))
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":"KOR1910","bookCode":"GEN","chapter":1}"""
        }.andExpect {
            status { isForbidden() }
        }

        mockMvc.post("/api/v1/admin/bible-commentaries/generation-jobs") {
            with(authentication(adminAuthentication()))
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":"KOR1910","bookCode":"GEN","chapter":1}"""
        }.andExpect {
            status { isAccepted() }
            jsonPath("$.status") { value("PENDING") }
            jsonPath("$.bookCode") { value("GEN") }
        }
    }

    @Test
    fun `failed automatic evaluation is retained but cannot be published`() {
        val rejected = createDraft(failingEvaluation())

        kotlin.test.assertEquals(BibleCommentaryStatus.REJECTED, rejected.status)
        kotlin.test.assertEquals(BibleCommentaryEvaluationStatus.FAILED, rejected.evaluationStatus)
        assertThrows<IllegalStateException> {
            commentaryService.publish(rejected.id, adminId, 50, "자동 평가 실패 내용을 확인했습니다.")
        }
    }

    @Test
    fun `editing a draft creates a new revision and review history`() {
        val draft = createDraft()
        val generated = generatedCommentary()
        val edited = commentaryService.editDraft(
            draft.id,
            adminId,
            BibleCommentaryDraftEdit(
                summary = generated.summary + " 관리자가 문장을 보완했습니다.",
                historicalContext = generated.historicalContext,
                sections = generated.sections,
                keyThemes = generated.keyThemes,
                crossReferences = generated.crossReferences,
                reflectionQuestions = generated.reflectionQuestions,
                cautions = generated.cautions,
                reviewNotes = "본문 표현과 적용 질문을 보완했습니다.",
            ),
        )

        kotlin.test.assertEquals(2, edited.revision)
        kotlin.test.assertEquals(BibleCommentaryEvaluationStatus.MANUAL_REVIEW, edited.evaluationStatus)
        kotlin.test.assertEquals(BibleCommentaryStatus.SUPERSEDED, commentaryRepository.findById(draft.id).orElseThrow().status)
        kotlin.test.assertEquals(1, commentaryService.reviewEvents(edited.id).size)
    }

    @Test
    fun `only approved evidence is supplied to commentary generation`() {
        val evidence = evidenceService.create(
            CreateBibleCommentaryEvidenceCommand(
                version = "KOR1910",
                bookCode = "GEN",
                chapter = 1,
                evidenceType = BibleCommentaryEvidenceType.LITERARY,
                title = "창세기 1장의 문학적 구조",
                content = "반복되는 선언과 평가 표현은 창조 본문의 질서 있는 진행을 보여주는 문학적 단서입니다.",
                sourceName = "My Pasture 검수 메모",
                sourceUrl = null,
                licenseNote = "서비스 내부 작성 및 사용 승인",
            ),
            adminId,
        )

        kotlin.test.assertEquals(BibleCommentaryEvidenceStatus.DRAFT, evidence.status)
        kotlin.test.assertTrue(evidenceService.approvedFor("KOR1910", "GEN", 1).isEmpty())
        evidenceService.approve(evidence.id, adminId)
        kotlin.test.assertEquals(listOf(evidence.id), evidenceService.approvedFor("KOR1910", "GEN", 1).map { it.id })
    }

    @Test
    fun `book generation keeps successful chapters when another chapter is already queued`() {
        commentaryService.queueGeneration("KOR1910", "GEN", 1, adminId)
        val result = batchService.queueBookGeneration("KOR1910", "GEN", false, adminId)

        kotlin.test.assertEquals(listOf(2), result.queuedChapters)
        kotlin.test.assertEquals(listOf(1), result.skippedChapters)
        kotlin.test.assertTrue(result.unavailableChapters.contains(3))
        kotlin.test.assertEquals(2, jobRepository.count())
    }

    private fun createDraft(evaluation: BibleCommentaryEvaluation = passingEvaluation()) =
        commentaryService.queueGeneration("KOR1910", "GEN", 1, adminId).let { queued ->
            val job = jobRepository.findById(queued.id).orElseThrow()
            job.start(Instant.now())
            jobRepository.saveAndFlush(job)
            commentaryService.saveGeneratedDraft(queued.id, generatedCommentary(), evaluation, "test-prompt-v1", 4)
        }

    private fun generatedCommentary() =
        GeneratedBibleCommentary(
            summary = "창세기 첫 장은 하나님이 세상을 질서 있게 창조하시는 시작을 보여주며, 모든 피조물이 하나님의 뜻 안에서 자리를 얻는 과정을 설명합니다.",
            historicalContext = "고대 근동의 창조 이야기들과 대화하면서도 창조의 주권이 오직 하나님께 있음을 선명하게 전하는 본문입니다.",
            sections = listOf(
                GeneratedCommentarySection(1, 1, "창조의 시작", "첫 절은 이야기 전체의 주체와 시작점을 분명하게 제시합니다."),
                GeneratedCommentarySection(2, 2, "질서를 향한 움직임", "둘째 절은 아직 정돈되지 않은 상태에서도 하나님의 역사가 이어짐을 보여줍니다."),
            ),
            keyThemes = listOf("창조", "하나님의 주권"),
            crossReferences = listOf(GeneratedCommentaryReference("GEN", 1, 1, "같은 장의 시작 선언을 다시 확인합니다.")),
            reflectionQuestions = listOf("내 삶에서 하나님의 질서를 기다리는 영역은 어디입니까?"),
            cautions = listOf("본문이 답하려는 신앙적 질문과 현대 과학의 설명 방식을 단순히 동일시하지 않습니다."),
            model = "test-model",
            promptTokens = 120,
            completionTokens = 220,
            estimatedCostUsd = BigDecimal("0.0005"),
        )

    private fun passingEvaluation() = BibleCommentaryEvaluation(
        passed = true,
        groundingScore = 5,
        historicalEvidenceScore = 4,
        referenceRelevanceScore = 5,
        theologicalNeutralityScore = 4,
        clarityScore = 5,
        fabricatedScripture = false,
        unsupportedHistoricalClaims = false,
        issues = emptyList(),
        model = "test-evaluator",
        promptTokens = 40,
        completionTokens = 30,
        estimatedCostUsd = BigDecimal("0.0001"),
    )

    private fun failingEvaluation() = passingEvaluation().copy(
        passed = false,
        historicalEvidenceScore = 2,
        unsupportedHistoricalClaims = true,
        issues = listOf("승인된 근거에 없는 역사적 설명이 포함되어 있습니다."),
    )

    private fun adminAuthentication(): UsernamePasswordAuthenticationToken {
        val principal = JwtPrincipal(
            userId = adminId,
            email = "admin@mypasture.test",
            displayName = "관리자",
            role = UserRole.ADMIN,
            subscriptionTier = SubscriptionTier.PREMIUM,
            authVersion = 0,
        )
        return UsernamePasswordAuthenticationToken(
            principal,
            null,
            listOf(SimpleGrantedAuthority("ROLE_ADMIN")),
        )
    }
}
