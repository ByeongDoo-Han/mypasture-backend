package com.example.mypasture.ai.application

import com.example.mypasture.ai.domain.AiAssistantExperiencePolicy
import com.example.mypasture.ai.domain.AiInteractionMode
import com.example.mypasture.user.domain.SubscriptionTier
import org.springframework.stereotype.Component

/** 구독 등급별 AI 응답 깊이와 대화 연속성 정책을 한 곳에서 결정합니다. */
@Component
class AiAssistantExperiencePolicyResolver {
    fun resolve(subscriptionTier: SubscriptionTier): AiAssistantExperiencePolicy =
        when (subscriptionTier) {
            SubscriptionTier.FREE -> AiAssistantExperiencePolicy(
                interactionMode = AiInteractionMode.ONE_SHOT,
                scriptureCount = 3,
                includePrayer = true,
                allowFollowUp = false,
                maxContextTurns = 0,
            )
            SubscriptionTier.PREMIUM -> AiAssistantExperiencePolicy(
                interactionMode = AiInteractionMode.GUIDED_DIALOGUE,
                scriptureCount = 5,
                includePrayer = true,
                allowFollowUp = true,
                maxContextTurns = 4,
            )
        }
}
