package com.example.mypasture.config

import com.example.mypasture.auth.infrastructure.JwtTokenProvider
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.user.domain.UserEntity
import com.example.mypasture.user.domain.UserRole
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** Swagger 화면과 OpenAPI JSON을 활성 관리자 계정만 열 수 있는지 확인합니다. */
@SpringBootTest
@AutoConfigureMockMvc
class SwaggerAccessSecurityTests(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val jwt: JwtTokenProvider,
) {
    @Test
    fun `swagger entry and specification paths require active admin`() {
        val member = users.saveAndFlush(UserEntity(displayName = "문서 접근 일반 사용자"))
        val admin = users.saveAndFlush(UserEntity(displayName = "문서 접근 관리자", role = UserRole.ADMIN))
        val memberToken = jwt.issueAccessToken(member).accessToken
        val adminToken = jwt.issueAccessToken(admin).accessToken

        for (path in listOf("/swagger-ui.html", "/swagger-ui/index.html", "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/swagger-config")) {
            mvc.get(path).andExpect { status { isUnauthorized() } }
            mvc.get(path) { header("Authorization", "Bearer $memberToken") }
                .andExpect { status { isForbidden() } }
            mvc.get(path) { header("Authorization", "Bearer $adminToken") }
                .andExpect { status { if (path == "/swagger-ui.html") is3xxRedirection() else is2xxSuccessful() } }
        }

        admin.invalidateSessions(java.time.Instant.now())
        users.saveAndFlush(admin)
        mvc.get("/v3/api-docs") { header("Authorization", "Bearer $adminToken") }
            .andExpect { status { isUnauthorized() } }
    }
}
