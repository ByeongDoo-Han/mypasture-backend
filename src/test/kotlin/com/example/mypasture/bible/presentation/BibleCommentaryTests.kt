package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.bible.application.BibleCommentaryService
import com.example.mypasture.bible.application.GeneratedBibleCommentary
import com.example.mypasture.bible.application.GeneratedCommentaryReference
import com.example.mypasture.bible.application.GeneratedCommentarySection
import com.example.mypasture.bible.infrastructure.BibleChapterCommentaryRepository
import com.example.mypasture.bible.infrastructure.BibleCommentaryGenerationJobRepository
import com.example.mypasture.user.domain.SubscriptionTier
import com.example.mypasture.user.domain.UserRole
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
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
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html"])
class BibleCommentaryTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val commentaryService: BibleCommentaryService,
    @Autowired private val commentaryRepository: BibleChapterCommentaryRepository,
    @Autowired private val jobRepository: BibleCommentaryGenerationJobRepository,
) {
    private val adminId = UUID.randomUUID()

    @BeforeEach
    fun cleanUp() {
        commentaryRepository.deleteAll()
        jobRepository.deleteAll()
    }

    @Test
    fun `published commentary is returned without generating at request time`() {
        val draft = createDraft()
        commentaryService.publish(draft.id, adminId, 92)

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

    private fun createDraft() =
        commentaryService.queueGeneration("KOR1910", "GEN", 1, adminId).let { queued ->
            val job = jobRepository.findById(queued.id).orElseThrow()
            job.start(Instant.now())
            jobRepository.saveAndFlush(job)
            commentaryService.saveGeneratedDraft(queued.id, generatedCommentary(), "test-prompt-v1")
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
