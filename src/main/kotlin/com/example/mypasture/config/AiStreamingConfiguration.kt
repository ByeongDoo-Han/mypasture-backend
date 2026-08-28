package com.example.mypasture.config

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/** SSE 연결 수를 제한하면서 외부 AI 대기 중 실행 thread를 재사용하는 coroutine runner입니다. */
@Component
class AiStreamingCoroutineRunner(properties: AiProperties) {
    private val capacity = properties.streamMaxPoolSize + properties.streamQueueCapacity
    private val permits = Semaphore(capacity)
    private val dispatcher: ExecutorCoroutineDispatcher = Executors
        .newFixedThreadPool(properties.streamMaxPoolSize, NamedThreadFactory())
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    fun tryLaunch(block: suspend () -> Unit): Boolean {
        if (!permits.tryAcquire()) return false
        scope.launch {
            try {
                block()
            } finally {
                permits.release()
            }
        }
        return true
    }

    /** 스트리밍 통합 테스트와 운영 진단에서 실행 중인 작업이 모두 끝났는지 확인합니다. */
    internal fun isIdle(): Boolean = permits.availablePermits() == capacity

    @PreDestroy
    fun shutdown() {
        scope.cancel()
        dispatcher.close()
    }

    private class NamedThreadFactory : ThreadFactory {
        private val sequence = AtomicInteger()
        override fun newThread(task: Runnable): Thread = Thread(task, "ai-stream-${sequence.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
}
