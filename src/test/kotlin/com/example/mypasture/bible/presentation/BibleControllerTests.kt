package com.example.mypasture.bible.presentation

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** BibleController의 1910 성경 목록/장/절/검색 조회 HTTP 흐름을 검증하는 통합 테스트입니다. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true"])
class BibleControllerTests(
    @Autowired private val mockMvc: MockMvc,
) {
    @Test
    fun `versions returns kor1910`() {
        mockMvc.get("/api/v1/bibles/versions")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("KOR1910") }
                jsonPath("$[0].copyright") { value("Public Domain") }
            }
    }

    @Test
    fun `books returns canonical bible book list`() {
        mockMvc.get("/api/v1/bibles/KOR1910/books")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("GEN") }
                jsonPath("$[0].name") { value("창세기") }
                jsonPath("$[0].chapterCount") { value(50) }
                jsonPath("$[65].code") { value("REV") }
            }
    }

    @Test
    fun `chapter returns all verses in selected chapter`() {
        mockMvc.get("/api/v1/bibles/KOR1910/books/GEN/chapters/1")
            .andExpect {
                status { isOk() }
                jsonPath("$.book.code") { value("GEN") }
                jsonPath("$.chapter") { value(1) }
                jsonPath("$.contentStatus") { value("AVAILABLE") }
                jsonPath("$.next.chapter") { value(2) }
                jsonPath("$.verses.length()") { value(2) }
                jsonPath("$.verses[0].verse") { value(1) }
                jsonPath("$.verses[0].text") { value("태초에 하나님이 천지를 창조하시니라") }
            }
    }

    @Test
    fun `missing source chapter returns empty chapter metadata`() {
        mockMvc.get("/api/v1/bibles/KOR1910/books/1PE/chapters/5")
            .andExpect {
                status { isOk() }
                jsonPath("$.book.code") { value("1PE") }
                jsonPath("$.chapter") { value(5) }
                jsonPath("$.contentStatus") { value("EMPTY") }
                jsonPath("$.verses.length()") { value(0) }
            }
    }

    @Test
    fun `psalm three digit source filename is imported`() {
        mockMvc.get("/api/v1/bibles/KOR1910/books/PSA/chapters/1")
            .andExpect {
                status { isOk() }
                jsonPath("$.contentStatus") { value("AVAILABLE") }
                jsonPath("$.verses[0].verse") { value(1) }
            }
    }

    @Test
    fun `search returns verses containing keyword with pagination`() {
        mockMvc.get("/api/v1/bibles/KOR1910/search") {
            param("keyword", "하나님")
            param("page", "0")
            param("size", "1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.keyword") { value("하나님") }
            jsonPath("$.total") { value(2) }
            jsonPath("$.hasNext") { value(true) }
            jsonPath("$.results.length()") { value(1) }
            jsonPath("$.results[0].bookCode") { value("GEN") }
            jsonPath("$.results[0].chapter") { value(1) }
            jsonPath("$.results[0].verse") { value(1) }
        }
    }

    @Test
    fun `search returns requested page`() {
        mockMvc.get("/api/v1/bibles/KOR1910/search") {
            param("keyword", "하나님")
            param("page", "1")
            param("size", "1")
        }.andExpect {
            status { isOk() }
            jsonPath("$.hasNext") { value(false) }
            jsonPath("$.results[0].verse") { value(2) }
        }
    }

    @Test
    fun `verse returns selected verse only`() {
        mockMvc.get("/api/v1/bibles/KOR1910/books/창세기/chapters/1/verses/2")
            .andExpect {
                status { isOk() }
                jsonPath("$.bookName") { value("창세기") }
                jsonPath("$.chapter") { value(1) }
                jsonPath("$.verse") { value(2) }
            }
    }

    @Test
    fun `missing verse returns not found`() {
        mockMvc.get("/api/v1/bibles/KOR1910/books/GEN/chapters/1/verses/999")
            .andExpect {
                status { isNotFound() }
                jsonPath("$.code") { value("BIBLE_NOT_FOUND") }
            }
    }
}
