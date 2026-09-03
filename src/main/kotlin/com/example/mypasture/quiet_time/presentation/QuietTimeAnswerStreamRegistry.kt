package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.config.QuietTimeRealtimeProperties
import com.example.mypasture.moderation.application.ModerationVisibilityService
import com.example.mypasture.moderation.domain.ModerationTargetType
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
    private val moderationVisibility: ModerationVisibilityService,
) {
    private val emitters = ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, Subscriber>>()

    fun subscribe(sessionId: UUID, userId: UUID): SseEmitter {
        val emitterId = UUID.randomUUID()
        val emitter = SseEmitter(properties.sseTimeoutMs)
        val sessionEmitters = emitters.computeIfAbsent(sessionId) { ConcurrentHashMap() }
        sessionEmitters[emitterId] = Subscriber(userId, emitter)
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
        if (moderationVisibility.isHidden(ModerationTargetType.QUIET_TIME_ANSWER, answer.id)) return
        val blockerUserIds = answer.userId?.let(moderationVisibility::blockerUserIds).orEmpty()
        sendToSession(
            answer.sessionId,
            SseEmitter.event()
                .id("${answer.id}:${answer.updatedAt.toEpochMilli()}")
                .name("answer-updated")
                .data(answer.toResponse()),
            excludedUserIds = blockerUserIds,
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
        emitters.remove(sessionId)?.values?.forEach { subscriber -> runCatching { subscriber.emitter.complete() } }
    }

    private fun sendToSession(
        sessionId: UUID,
        event: SseEmitter.SseEventBuilder,
        excludedUserIds: Set<UUID> = emptySet(),
    ) {
        emitters[sessionId]?.forEach { (emitterId, subscriber) ->
            if (subscriber.userId in excludedUserIds) return@forEach
            try {
                subscriber.emitter.send(event)
            } catch (_: Exception) {
                remove(sessionId, emitterId)
                // 컨테이너가 이미 오류 콜백을 끝낸 emitter는 complete도 실패할 수 있습니다.
                runCatching { subscriber.emitter.complete() }
            }
        }
    }

    private fun remove(sessionId: UUID, emitterId: UUID) {
        val sessionEmitters = emitters[sessionId] ?: return
        sessionEmitters.remove(emitterId)
        if (sessionEmitters.isEmpty()) emitters.remove(sessionId, sessionEmitters)
    }

    private data class Subscriber(val userId: UUID, val emitter: SseEmitter)
}
