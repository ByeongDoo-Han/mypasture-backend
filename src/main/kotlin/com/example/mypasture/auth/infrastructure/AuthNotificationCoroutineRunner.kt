package com.example.mypasture.auth.infrastructure

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.Executors

/** SMTP 같은 블로킹 알림 I/O를 요청 스레드 밖의 제한된 코루틴에서 실행합니다. */
@Component
class AuthNotificationCoroutineRunner {
    private val dispatcher = Executors.newFixedThreadPool(MAX_CONCURRENT_EMAILS) { task ->
        Thread(task, "auth-email-io").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
        dispatcher.close()
    }

    companion object {
        private const val MAX_CONCURRENT_EMAILS = 8
    }
}
