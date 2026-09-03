package com.example.mypasture.moderation.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** QT 공유 전에 고위험 괴롭힘 표현과 운영 추가 차단어를 빠르게 거르는 1차 콘텐츠 필터입니다. */
@Component
class UserGeneratedContentGuard(
    @Value("\${app.moderation.blocked-terms:}") configuredTerms: String,
) {
    private val blockedTerms = (DEFAULT_BLOCKED_TERMS + configuredTerms.split(','))
        .map(::normalize)
        .filter(String::isNotBlank)
        .toSet()

    fun requireAllowed(content: String) {
        val normalized = normalize(content)
        if (blockedTerms.any(normalized::contains)) {
            throw UserGeneratedContentRejectedException()
        }
    }

    private fun normalize(value: String): String = value
        .lowercase()
        .replace(IGNORED_CHARACTERS, "")

    companion object {
        private val IGNORED_CHARACTERS = Regex("[\\s._*~!?,;:'\"`-]+")
        private val DEFAULT_BLOCKED_TERMS = setOf(
            "자살해",
            "죽어버려",
            "너는죽어야",
        )
    }
}

/** 고확신 금지 표현으로 인해 게시 전 거절된 사용자 콘텐츠입니다. */
class UserGeneratedContentRejectedException : RuntimeException("공동체 운영 정책에 따라 게시할 수 없는 표현이 포함되어 있습니다.")
