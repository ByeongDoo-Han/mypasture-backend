package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.config.QuietTimeRealtimeProperties
import com.example.mypasture.quiet_time.application.QuietTimeQuestionAnswerView
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 서버 인스턴스에 연결된 QT SSE 구독자를 세션별로 관리하고 답변 이벤트를 전달합니다. */
@Component
class QuietTimeAnswerStreamRegistry(
    private val properties: QuietTimeRealtimeProperties,
) {
    private val emitters = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, SseEmitter>>()

    fun subscribe(sessionId: UUID): SseEmitter {
        val emitterId = UUID.randomUUID()
        val emitter = SseEmitter(properties.sseTimeoutMs)
        val sessionEmitters = emitters.computeIfAbsent(sessionId) { ConcurrentHashMap() }
        sessionEmitters[emitterId] = emitter
        val cleanup = { remove(sessionId, emitterId) }
        emitter.onCompletion(cleanup)
        emitter.onTimeout(cleanup)
        emitter.onError { cleanup() }
        try {
            emitter.send(
                SseEmitter.event()
                    .name("connected")
                    .data(mapOf("sessionId" to sessionId.toString(), "connectedAt" to Instant.now().toString())),
            )
        } catch (error: Exception) {
            cleanup()
            runCatching { emitter.completeWithError(error) }
        }
        return emitter
    }

    fun broadcast(answer: QuietTimeQuestionAnswerView) {
        sendToSession(
            answer.sessionId,
            SseEmitter.event()
                .id("${answer.id}:${answer.updatedAt.toEpochMilli()}")
                .name("answer-updated")
                .data(answer.toResponse()),
        )
    }

    @Scheduled(fixedDelayString = "\${app.quiet-time.realtime.heartbeat-delay-ms:15000}")
    fun heartbeat() {
        emitters.keys.forEach { sessionId ->
            sendToSession(
                sessionId,
                SseEmitter.event().name("heartbeat").data(mapOf("sentAt" to Instant.now().toString())),
            )
        }
    }

    fun completeSession(sessionId: UUID) {
        emitters.remove(sessionId)?.values?.forEach { emitter -> runCatching { emitter.complete() } }
    }

    private fun sendToSession(sessionId: UUID, event: SseEmitter.SseEventBuilder) {
        emitters[sessionId]?.forEach { (emitterId, emitter) ->
            try {
                emitter.send(event)
            } catch (_: Exception) {
                remove(sessionId, emitterId)
                // 컨테이너가 이미 오류 콜백을 끝낸 emitter는 complete도 실패할 수 있습니다.
                runCatching { emitter.complete() }
            }
        }
    }

    private fun remove(sessionId: UUID, emitterId: UUID) {
        val sessionEmitters = emitters[sessionId] ?: return
        sessionEmitters.remove(emitterId)
        if (sessionEmitters.isEmpty()) emitters.remove(sessionId, sessionEmitters)
    }
}
