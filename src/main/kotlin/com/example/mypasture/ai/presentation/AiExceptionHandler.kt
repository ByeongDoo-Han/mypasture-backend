package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.*
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

/** AI 정책, 안전, 외부 제공자 오류를 안정적인 코드로 변환합니다. */
@RestControllerAdvice(assignableTypes = [AiController::class, AiEmbeddingAdminController::class])
class AiExceptionHandler {
    @ExceptionHandler(AiInputTooLongException::class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun inputTooLong(exception: AiInputTooLongException) = AiErrorResponse("AI_INPUT_TOO_LONG", exception.message ?: "AI input is too long")
    @ExceptionHandler(AiQuotaExceededException::class) @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun quotaExceeded(exception: AiQuotaExceededException) = AiErrorResponse("AI_QUOTA_EXCEEDED", exception.message ?: "Daily AI quota exceeded")
    @ExceptionHandler(AiGroundingNotFoundException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun groundingNotFound(exception: AiGroundingNotFoundException) = AiErrorResponse("AI_GROUNDING_NOT_FOUND", exception.message ?: "No relevant Bible verses found")
    @ExceptionHandler(AiSafetyException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun safety(exception: AiSafetyException) = AiErrorResponse("AI_SAFETY_ESCALATION", exception.message ?: "즉시 도움을 요청해 주세요.")
    @ExceptionHandler(AiProviderException::class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun provider(exception: AiProviderException) = AiErrorResponse("AI_PROVIDER_UNAVAILABLE", exception.message ?: "AI service unavailable")
    @ExceptionHandler(BibleEmbeddingIndexUnavailableException::class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun embeddingUnavailable(exception: BibleEmbeddingIndexUnavailableException) =
        AiErrorResponse("AI_EMBEDDING_INDEX_UNAVAILABLE", exception.message ?: "Embedding index unavailable")
}
data class AiErrorResponse(val code: String, val message: String)
