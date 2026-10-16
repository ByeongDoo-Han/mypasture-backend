package com.example.mypasture.bible.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.util.UUID

/** 다중 구절 메모의 CRUD, 수정 충돌, 사용자 소유권과 입력 제한을 HTTP 수준에서 검증합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = [
        "app.bible.kor1910-html-path=src/test/resources/bible/kor_html",
        "app.bible.import-enabled=true",
        "app.firebase.enabled=false",
    ],
)
class BibleNoteControllerTests(
    @Autowired private val mockMvc: MockMvc,
    @Autowired private val objectMapper: ObjectMapper,
) {
    @Test
    fun `owner creates lists updates and deletes a multi verse note`() {
        val ownerToken = createUserToken("owner")
        val created = mockMvc.post("/api/v1/bible-notes") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = createRequest("창조의 시작을 기억합니다.", 1, 2)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.revision") { value(0) }
            jsonPath("$.content") { value("창조의 시작을 기억합니다.") }
            jsonPath("$.verses.length()") { value(2) }
            jsonPath("$.verses[0].bookName") { value("창세기") }
            jsonPath("$.verses[1].verse") { value(2) }
        }.andReturn()
        val noteId = objectMapper.readTree(created.response.contentAsString)["id"].asText()

        mockMvc.get("/api/v1/bible-notes") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(1) }
            jsonPath("$.items[0].id") { value(noteId) }
        }

        mockMvc.put("/api/v1/bible-notes/$noteId") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = updateRequest(0, "첫 절에 집중해 다시 묵상합니다.", 1)
        }.andExpect {
            status { isOk() }
            jsonPath("$.revision") { value(1) }
            jsonPath("$.content") { value("첫 절에 집중해 다시 묵상합니다.") }
            jsonPath("$.verses.length()") { value(1) }
        }

        mockMvc.put("/api/v1/bible-notes/$noteId") {
            header("Authorization", "Bearer $ownerToken")
            contentType = MediaType.APPLICATION_JSON
            content = updateRequest(0, "오래된 화면의 저장입니다.", 2)
        }.andExpect {
            status { isConflict() }
            jsonPath("$.code") { value("BIBLE_NOTE_CONFLICT") }
        }

        val otherToken = createUserToken("other")
        mockMvc.get("/api/v1/bible-notes") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.totalElements") { value(0) }
        }

        mockMvc.put("/api/v1/bible-notes/$noteId") {
            header("Authorization", "Bearer $otherToken")
            contentType = MediaType.APPLICATION_JSON
            content = updateRequest(0, "다른 사용자의 수정입니다.", 1)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("BIBLE_NOTE_NOT_FOUND") }
        }

        mockMvc.delete("/api/v1/bible-notes/$noteId") {
            header("Authorization", "Bearer $otherToken")
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("BIBLE_NOTE_NOT_FOUND") }
        }

        mockMvc.delete("/api/v1/bible-notes/$noteId") {
            header("Authorization", "Bearer $ownerToken")
        }.andExpect { status { isNoContent() } }
    }

    @Test
    fun `note endpoints require authentication and validate content verses and page`() {
        mockMvc.get("/api/v1/bible-notes")
            .andExpect { status { isUnauthorized() } }

        val token = createUserToken("invalid")
        mockMvc.post("/api/v1/bible-notes") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = createRequest("   ", 1)
        }.andExpect { status { isBadRequest() } }

        mockMvc.post("/api/v1/bible-notes") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = createRequest("중복 구절", 1, 1)
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_BIBLE_REQUEST") }
        }

        mockMvc.post("/api/v1/bible-notes") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = createRequest("없는 구절", 999)
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.code") { value("BIBLE_NOT_FOUND") }
        }

        mockMvc.get("/api/v1/bible-notes?page=-1") {
            header("Authorization", "Bearer $token")
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.code") { value("INVALID_BIBLE_REQUEST") }
        }
    }

    private fun createRequest(content: String, vararg verses: Int) =
        noteRequest(content = content, expectedRevision = null, verses = verses)

    private fun updateRequest(expectedRevision: Long, content: String, vararg verses: Int) =
        noteRequest(content = content, expectedRevision = expectedRevision, verses = verses)

    private fun noteRequest(content: String, expectedRevision: Long?, verses: IntArray): String =
        objectMapper.writeValueAsString(
            buildMap<String, Any> {
                if (expectedRevision != null) put("expectedRevision", expectedRevision)
                put("content", content)
                put(
                    "verses",
                    verses.map { verse ->
                        mapOf("version" to "KOR1910", "bookCode" to "GEN", "chapter" to 1, "verse" to verse)
                    },
                )
            },
        )

    private fun createUserToken(prefix: String): String {
        val email = "note-$prefix-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }

        val result = mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return objectMapper.readTree(result.response.contentAsString)["token"].asText()
    }
}
