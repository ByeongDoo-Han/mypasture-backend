package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname
import com.example.mypasture.bible.domain.BibleBookEntity
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleChapterEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.domain.Testament
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleChapterEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/** 선택 말씀의 비동기 질문 생성 완료에도 인증 경계가 유지되는지 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@TestPropertySource(properties = ["app.ai.mock-enabled=true"])
class QuietTimeQuestionGenerationSecurityTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
    @Autowired private val books: BibleBookEntityRepository,
    @Autowired private val chapters: BibleChapterEntityRepository,
    @Autowired private val verses: BibleVerseEntityRepository,
) {
    @Test
    fun `질문 생성은 처음부터 인증을 요구하고 비동기 작업을 완료한다`() {
        seedVerse()
        val token = issueToken()
        val passage = mockMvc.post("/api/v1/quiet-time/passages/selected") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"version":"KOR1910","bookCode":"JHN","chapter":13,"startVerse":34,"endVerse":34}"""
        }.andExpect { status { isOk() } }.andReturn()
        val passageId = objectMapper.readTree(passage.response.contentAsString).path("id").asText()

        mockMvc.post("/api/v1/quiet-time/passages/$passageId/question-generations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"style":"BALANCED"}"""
        }.andExpect { status { isUnauthorized() } }

        val initial = mockMvc.post("/api/v1/quiet-time/passages/$passageId/question-generations") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"style":"BALANCED"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()

        mockMvc.perform(asyncDispatch(initial))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("READY"))
    }

    private fun seedVerse() {
        val book = books.findByVersionAndCode("KOR1910", "JHN")
            ?: books.saveAndFlush(BibleBookEntity(version = "KOR1910", code = "JHN", name = "요한복음", testament = Testament.NEW, displayOrder = 43))
        chapters.findByVersionAndBook_IdAndChapter("KOR1910", book.id, 13)
            ?: chapters.saveAndFlush(BibleChapterEntity(version = "KOR1910", book = book, chapter = 13, contentStatus = BibleChapterContentStatus.AVAILABLE, verseCount = 1))
        verses.findByVersionAndBook_IdAndChapterAndVerse("KOR1910", book.id, 13, 34)
            ?: verses.saveAndFlush(BibleVerseEntity(version = "KOR1910", book = book, chapter = 13, verse = 34,
                text = "새 계명을 너희에게 주노니 서로 사랑하라"))
    }

    private fun issueToken(): String {
        val email = "qt-async-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }
        val login = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(login.response.contentAsString).path("token").asText()
    }
}
