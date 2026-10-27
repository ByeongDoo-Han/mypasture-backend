package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.AiProviderFailureKind

/** Responses API의 구조화 JSON 조각에서 answer 문자열만 완성되는 대로 꺼냅니다. */
internal class StructuredAnswerDeltaExtractor {
    private val prefix = StringBuilder()
    private val unicodeDigits = StringBuilder()
    private var started = false
    private var escaped = false
    private var readingUnicode = false
    private var highSurrogate: Char? = null
    var complete: Boolean = false
        private set

    fun accept(delta: String): String {
        val answer = StringBuilder()
        for (character in delta) {
            if (!started) {
                prefix.append(character)
                val match = ANSWER_FIELD.find(prefix)
                if (match != null) {
                    started = true
                    val remaining = prefix.substring(match.range.last + 1)
                    prefix.clear()
                    remaining.forEach { readAnswerCharacter(it, answer) }
                } else if (prefix.length > MAX_PREFIX_LENGTH) {
                    throw invalidOutput()
                }
            } else {
                readAnswerCharacter(character, answer)
            }
        }
        return answer.toString()
    }

    private fun readAnswerCharacter(character: Char, answer: StringBuilder) {
        if (complete) return
        if (readingUnicode) {
            if (character !in '0'..'9' && character !in 'a'..'f' && character !in 'A'..'F') throw invalidOutput()
            unicodeDigits.append(character)
            if (unicodeDigits.length == 4) {
                appendDecoded(unicodeDigits.toString().toInt(16).toChar(), answer)
                unicodeDigits.clear()
                readingUnicode = false
            }
            return
        }
        if (escaped) {
            escaped = false
            when (character) {
                '"', '\\', '/' -> appendDecoded(character, answer)
                'b' -> appendDecoded('\b', answer)
                'f' -> appendDecoded('\u000C', answer)
                'n' -> appendDecoded('\n', answer)
                'r' -> appendDecoded('\r', answer)
                't' -> appendDecoded('\t', answer)
                'u' -> readingUnicode = true
                else -> throw invalidOutput()
            }
            return
        }
        when (character) {
            '\\' -> escaped = true
            '"' -> {
                if (highSurrogate != null) throw invalidOutput()
                complete = true
            }
            else -> {
                if (character.code < 0x20) throw invalidOutput()
                appendDecoded(character, answer)
            }
        }
    }

    private fun appendDecoded(character: Char, answer: StringBuilder) {
        val pendingHigh = highSurrogate
        if (pendingHigh != null) {
            if (!character.isLowSurrogate()) throw invalidOutput()
            answer.append(pendingHigh).append(character)
            highSurrogate = null
        } else if (character.isHighSurrogate()) {
            highSurrogate = character
        } else {
            if (character.isLowSurrogate()) throw invalidOutput()
            answer.append(character)
        }
    }

    private fun invalidOutput() = AiProviderException(
        "AI 답변의 구조화 조각을 해석할 수 없습니다.",
        failureKind = AiProviderFailureKind.INVALID_OUTPUT,
    )

    companion object {
        private val ANSWER_FIELD = Regex("\\\"answer\\\"\\s*:\\s*\\\"")
        private const val MAX_PREFIX_LENGTH = 1_024
    }
}
