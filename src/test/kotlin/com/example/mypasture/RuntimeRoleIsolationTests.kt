package com.example.mypasture

import com.example.mypasture.auth.infrastructure.AuthTokenCleanupJob
import com.example.mypasture.config.CorsProperties
import com.example.mypasture.config.SecurityConfig
import com.example.mypasture.daily_word.infrastructure.DailyWordIncidentEmailWorker
import com.example.mypasture.notification.infrastructure.NotificationDispatchJob
import com.example.mypasture.quiet_time.presentation.QuietTimeAnswerStreamRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.WebApplicationType
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.SecurityFilterChain

/** Cloud Run API에서 배치 작업이 중복 실행되지 않고 SSE heartbeat만 유지되는지 검증합니다. */
@SpringBootTest(
    properties = [
        "app.worker.enabled=false",
    ],
)
class RuntimeRoleIsolationTests(
    @Autowired private val context: ApplicationContext,
) {
    @Test
    fun `api role excludes singleton scheduled workers`() {
        assertThat(context.getBeansOfType(AuthTokenCleanupJob::class.java)).isEmpty()
        assertThat(context.getBeansOfType(DailyWordIncidentEmailWorker::class.java)).isEmpty()
        assertThat(context.getBeansOfType(NotificationDispatchJob::class.java)).isEmpty()
        assertThat(context.getBeansOfType(QuietTimeAnswerStreamRegistry::class.java)).hasSize(1)
    }

    @Test
    fun `migration role disables the web server`() {
        assertThat(createMypastureApplication(migrationOnly = true).webApplicationType)
            .isEqualTo(WebApplicationType.NONE)
    }

    @Test
    fun `non web runtime keeps shared security beans but excludes servlet filter chain`() {
        ApplicationContextRunner()
            .withBean(CorsProperties::class.java, { CorsProperties() })
            .withUserConfiguration(SecurityConfig::class.java)
            .run { nonWebContext ->
                assertThat(nonWebContext).hasSingleBean(PasswordEncoder::class.java)
                assertThat(nonWebContext).doesNotHaveBean(SecurityFilterChain::class.java)
            }
    }
}
