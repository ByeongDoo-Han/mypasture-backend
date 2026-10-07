package com.example.mypasture.ai.domain

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** 원 질문 직전의 불변 입력 문맥을 30일 동안만 복원합니다. */
data class AiContextSnapshot(
    val schemaVersion: Int = 1,
    val capturedAt: Instant,
    val expiresAt: Instant,
    val context: AiConversationContext,
) {
    companion object {
        fun capture(context: AiConversationContext, now: Instant = Instant.now()) = AiContextSnapshot(
            capturedAt = now.truncatedTo(ChronoUnit.MICROS),
            expiresAt = now.truncatedTo(ChronoUnit.MICROS).plus(30, ChronoUnit.DAYS),
            context = context.copy(turns = context.turns.map { it.copy() }),
        )
    }
}

/** 문맥 유무와 별개로 원 턴·그룹 identity를 보존해 삭제 후 늦은 재생성 완료를 차단합니다. */
data class AiRestoredContextSnapshot(
    val sourceTurnId: UUID,
    val sourceCiphertext: String?,
    val sourceExpiresAt: Instant?,
    val snapshot: AiContextSnapshot?,
    val sourceCreatedAt: Instant,
    val rootTurnId: UUID,
    val rootCreatedAt: Instant,
)

/** 외부 응답에 포함하지 않는 원자적 완료용 입력 문맥과 프리미엄 append 정책입니다. */
data class AiTurnCompletionContext(
    val snapshot: AiContextSnapshot? = null,
    val restoredSource: AiRestoredContextSnapshot? = null,
    val appendPolicy: AiAssistantExperiencePolicy? = null,
)
