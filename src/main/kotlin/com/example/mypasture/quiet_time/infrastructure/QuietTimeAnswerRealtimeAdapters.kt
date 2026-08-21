package com.example.mypasture.quiet_time.infrastructure

import com.example.mypasture.quiet_time.application.QuietTimeAnswerChangedEvent
import com.example.mypasture.quiet_time.application.QuietTimeAnswerRealtimePort
import com.example.mypasture.quiet_time.presentation.QuietTimeAnswerStreamRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.Message
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.concurrent.Executors

/** Redis를 사용하지 않는 단일 서버 환경에서 SSE 구독자에게 직접 답변을 전달합니다. */
@Component
@ConditionalOnProperty(
    prefix = "app.quiet-time.realtime",
    name = ["redis-enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class LocalQuietTimeAnswerRealtimeAdapter(
    private val streamRegistry: QuietTimeAnswerStreamRegistry,
) : QuietTimeAnswerRealtimePort {
    override suspend fun publish(event: QuietTimeAnswerChangedEvent) {
        streamRegistry.broadcast(event.answer)
    }
}

/** Redis Pub/Sub으로 답변 이벤트를 발행해 모든 서버 인스턴스의 SSE 구독자에게 전달합니다. */
@Component
@ConditionalOnProperty(prefix = "app.quiet-time.realtime", name = ["redis-enabled"], havingValue = "true")
class RedisQuietTimeAnswerRealtimeAdapter(
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : QuietTimeAnswerRealtimePort {
    override suspend fun publish(event: QuietTimeAnswerChangedEvent) {
        redisTemplate.convertAndSend(QUIET_TIME_ANSWER_CHANNEL, objectMapper.writeValueAsString(event))
    }
}

/** Redis 채널의 답변 이벤트를 현재 서버 인스턴스 SSE 연결로 중계합니다. */
@Configuration
@ConditionalOnProperty(prefix = "app.quiet-time.realtime", name = ["redis-enabled"], havingValue = "true")
class QuietTimeAnswerRedisSubscriberConfiguration {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun quietTimeAnswerRedisMessageListenerContainer(
        connectionFactory: RedisConnectionFactory,
        objectMapper: ObjectMapper,
        streamRegistry: QuietTimeAnswerStreamRegistry,
    ): RedisMessageListenerContainer =
        RedisMessageListenerContainer().apply {
            setConnectionFactory(connectionFactory)
            addMessageListener(
                { message: Message, _ ->
                    try {
                        val event = objectMapper.readValue(message.body, QuietTimeAnswerChangedEvent::class.java)
                        streamRegistry.broadcast(event.answer)
                    } catch (error: RuntimeException) {
                        logger.error("Failed to relay quiet time answer event from Redis", error)
                    }
                },
                ChannelTopic(QUIET_TIME_ANSWER_CHANNEL),
            )
        }
}

/** 트랜잭션 커밋 후 실시간 채널 발행을 제한된 코루틴으로 분리합니다. */
@Component
class QuietTimeAnswerRealtimeEventListener(
    private val realtimePort: QuietTimeAnswerRealtimePort,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val dispatcher = Executors.newFixedThreadPool(MAX_REALTIME_PUBLISHERS) { task ->
        Thread(task, "quiet-time-realtime").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAnswerChanged(event: QuietTimeAnswerChangedEvent) {
        scope.launch {
            try {
                realtimePort.publish(event)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: RuntimeException) {
                logger.error("Failed to publish quiet time answer event sessionId={}", event.answer.sessionId, error)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        scope.cancel()
        dispatcher.close()
    }

    companion object {
        private const val MAX_REALTIME_PUBLISHERS = 4
    }
}

internal const val QUIET_TIME_ANSWER_CHANNEL = "mypasture:quiet-time:answer-events"
