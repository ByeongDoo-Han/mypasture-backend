package com.example.mypasture.worship.presentation

import com.example.mypasture.auth.domain.uniqueTestNickname
import com.example.mypasture.bible.infrastructure.BibleImportCoordinator
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

/** 예배 기록의 50절 경계, CRUD, 수정 충돌과 사용자별 비공개 접근을 확인합니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true", "app.firebase.enabled=false"])
class WorshipRecordControllerTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val json: ObjectMapper,
    @Autowired private val bibleImporter: BibleImportCoordinator,
) {
    @Test
    fun `swagger exposes worship record contract and bearer authentication`() {
        mvc.get("/swagger-ui/index.html").andExpect { status { isOk() } }
        mvc.get("/v3/api-docs").andExpect {
            status { isOk() }
            jsonPath("$.paths['/api/v1/worship-records'].post.summary") { value("예배 기록 작성") }
            jsonPath("$.paths['/api/v1/worship-records'].post.security[0].bearerAuth") { exists() }
            jsonPath("$.components.securitySchemes.bearerAuth.scheme") { value("bearer") }
        }
    }

    @Test
    fun `owner creates updates and deletes while another user cannot read`() {
        val owner = token()
        val created = mvc.post("/api/v1/worship-records") {
            header("Authorization", "Bearer $owner")
            contentType = MediaType.APPLICATION_JSON
            content = request(1, 2)
        }.andExpect {
            status { isCreated() }
            jsonPath("$.bookName") { value("창세기") }
            jsonPath("$.revision") { value(0) }
        }.andReturn()
        val id = json.readTree(created.response.contentAsString)["id"].asText()

        mvc.get("/api/v1/worship-records/$id") { header("Authorization", "Bearer $owner") }
            .andExpect { status { isOk() }; jsonPath("$.endVerse") { value(2) } }
        mvc.get("/api/v1/worship-records") { header("Authorization", "Bearer $owner") }
            .andExpect { status { isOk() }; jsonPath("$.items[0].id") { value(id) } }

        val other = token()
        mvc.get("/api/v1/worship-records/$id") { header("Authorization", "Bearer $other") }
            .andExpect { status { isNotFound() } }
        mvc.delete("/api/v1/worship-records/$id") { header("Authorization", "Bearer $other") }
            .andExpect { status { isNotFound() } }

        mvc.put("/api/v1/worship-records/$id") {
            header("Authorization", "Bearer $owner")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("expectedRevision" to 0, "record" to input(1, 1)))
        }.andExpect { status { isOk() }; jsonPath("$.revision") { value(1) } }
        mvc.put("/api/v1/worship-records/$id") {
            header("Authorization", "Bearer $owner")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("expectedRevision" to 0, "record" to input(1, 2)))
        }.andExpect { status { isConflict() } }
        mvc.delete("/api/v1/worship-records/$id") { header("Authorization", "Bearer $owner") }
            .andExpect { status { isNoContent() } }
    }

    @Test
    fun `invalid range and missing verse are rejected`() {
        mvc.get("/api/v1/worship-records").andExpect { status { isUnauthorized() } }
        val owner = token()
        listOf(request(1, 51), request(2, 1), request(999, 999)).forEach { body ->
            mvc.post("/api/v1/worship-records") {
                header("Authorization", "Bearer $owner")
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isBadRequest() } }
        }
    }

    @Test
    fun `KRV local scripture version can be recorded`() {
        bibleImporter.importKrv()
        val owner = token()
        mvc.post("/api/v1/worship-records") {
            header("Authorization", "Bearer $owner")
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(input(1, 1) + ("bibleVersion" to "KRV"))
        }.andExpect { status { isCreated() }; jsonPath("$.bibleVersion") { value("KRV") } }
    }

    private fun request(start: Int, end: Int) = json.writeValueAsString(input(start, end))
    private fun input(start: Int, end: Int) = mapOf(
        "worshipDate" to "2026-10-18", "serviceType" to "SUNDAY", "title" to "주일 예배",
        "bibleVersion" to "KOR1910", "bookCode" to "GEN", "chapter" to 1,
        "startVerse" to start, "endVerse" to end, "heard" to "예배에서 기억한 말씀",
        "lifeConnection" to "오늘의 삶", "personalResponse" to "이웃에게 연락하기", "prayer" to "기도합니다",
    )

    private fun token(): String {
        val email = "worship-${UUID.randomUUID()}@example.com"
        val password = "password123!"
        mvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password","displayName":"${uniqueTestNickname()}"}"""
        }.andExpect { status { isCreated() } }
        val login = mvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andExpect { status { isOk() } }.andReturn()
        return json.readTree(login.response.contentAsString)["token"].asText()
    }
}
