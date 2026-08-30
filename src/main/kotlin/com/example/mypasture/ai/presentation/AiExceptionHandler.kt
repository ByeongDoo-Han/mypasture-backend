package com.example.mypasture.ai.presentation

import com.example.mypasture.ai.application.*
import com.example.mypasture.ai.domain.AiConversationUnavailableException
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

/** AI 정책, 안전, 외부 제공자 오류를 안정적인 코드로 변환합니다. */
@RestControllerAdvice(assignableTypes = [AiController::class, AiEmbeddingAdminController::class, AiRuntimeConfigAdminController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiExceptionHandler {
    @ExceptionHandler(AiAdminOperationConflictException::class) @ResponseStatus(HttpStatus.CONFLICT)
    fun adminConflict(exception: AiAdminOperationConflictException, request: HttpServletRequest) =
        AiErrorCode.ADMIN_OPERATION_CONFLICT.toResponse(request, exception.message ?: AiErrorCode.ADMIN_OPERATION_CONFLICT.defaultMessage)
    @ExceptionHandler(AiRuntimeConfigNotFoundException::class) @ResponseStatus(HttpStatus.NOT_FOUND)
    fun runtimeConfigNotFound(exception: AiRuntimeConfigNotFoundException, request: HttpServletRequest) =
        AiErrorCode.RUNTIME_CONFIG_NOT_FOUND.toResponse(request, exception.message ?: AiErrorCode.RUNTIME_CONFIG_NOT_FOUND.defaultMessage)
    @ExceptionHandler(IllegalArgumentException::class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidConfig(exception: IllegalArgumentException, request: HttpServletRequest) =
        AiErrorCode.INVALID_RUNTIME_CONFIG.toResponse(request, exception.message ?: AiErrorCode.INVALID_RUNTIME_CONFIG.defaultMessage)
    @ExceptionHandler(AiInputTooLongException::class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun inputTooLong(exception: AiInputTooLongException, request: HttpServletRequest) =
        AiErrorCode.INPUT_TOO_LONG.toResponse(request, exception.message ?: AiErrorCode.INPUT_TOO_LONG.defaultMessage)
    @ExceptionHandler(AiQuotaExceededException::class) @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    fun quotaExceeded(exception: AiQuotaExceededException, request: HttpServletRequest) =
        AiErrorCode.QUOTA_EXCEEDED.toResponse(request, exception.message ?: AiErrorCode.QUOTA_EXCEEDED.defaultMessage)
    @ExceptionHandler(AiGroundingNotFoundException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun groundingNotFound(exception: AiGroundingNotFoundException, request: HttpServletRequest) =
        AiErrorCode.GROUNDING_NOT_FOUND.toResponse(request, exception.message ?: AiErrorCode.GROUNDING_NOT_FOUND.defaultMessage)
    @ExceptionHandler(AiAnswerRejectedException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun answerRejected(exception: AiAnswerRejectedException, request: HttpServletRequest) =
        AiErrorCode.EVALUATION_REJECTED.toResponse(request, exception.message ?: AiErrorCode.EVALUATION_REJECTED.defaultMessage)
    @ExceptionHandler(AiSafetyException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun safety(exception: AiSafetyException, request: HttpServletRequest) =
        AiErrorCode.SAFETY_ESCALATION.toResponse(request, exception.message ?: AiErrorCode.SAFETY_ESCALATION.defaultMessage)
    @ExceptionHandler(AiProviderException::class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun provider(exception: AiProviderException, request: HttpServletRequest) =
        AiErrorCode.PROVIDER_UNAVAILABLE.toResponse(request, exception.message ?: AiErrorCode.PROVIDER_UNAVAILABLE.defaultMessage)
    @ExceptionHandler(AiConversationUnavailableException::class) @ResponseStatus(HttpStatus.NOT_FOUND)
    fun conversationUnavailable(exception: AiConversationUnavailableException, request: HttpServletRequest) =
        AiErrorCode.CONVERSATION_UNAVAILABLE.toResponse(request, exception.message ?: AiErrorCode.CONVERSATION_UNAVAILABLE.defaultMessage)
    @ExceptionHandler(BibleEmbeddingIndexUnavailableException::class) @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun embeddingUnavailable(exception: BibleEmbeddingIndexUnavailableException, request: HttpServletRequest) =
        AiErrorCode.EMBEDDING_INDEX_UNAVAILABLE.toResponse(request, exception.message ?: AiErrorCode.EMBEDDING_INDEX_UNAVAILABLE.defaultMessage)
}

/** AI 도메인이 일반 응답과 SSE에서 함께 사용하는 안정적인 오류 코드입니다. */
enum class AiErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : ErrorCode {
    INPUT_TOO_LONG("AI_INPUT_TOO_LONG", "AI input is too long"),
    QUOTA_EXCEEDED("AI_QUOTA_EXCEEDED", "Daily AI quota exceeded"),
    GROUNDING_NOT_FOUND("AI_GROUNDING_NOT_FOUND", "No relevant Bible verses found"),
    EVALUATION_REJECTED("AI_EVALUATION_REJECTED", "AI 답변이 품질 검증을 통과하지 못했습니다."),
    SAFETY_ESCALATION("AI_SAFETY_ESCALATION", "즉시 도움을 요청해 주세요."),
    PROVIDER_UNAVAILABLE("AI_PROVIDER_UNAVAILABLE", "AI service unavailable"),
    CONVERSATION_UNAVAILABLE("AI_CONVERSATION_UNAVAILABLE", "AI 대화를 찾을 수 없거나 이미 만료되었습니다."),
    EMBEDDING_INDEX_UNAVAILABLE("AI_EMBEDDING_INDEX_UNAVAILABLE", "Embedding index unavailable"),
    STREAM_FAILED("AI_STREAM_FAILED", "AI streaming 요청에 실패했습니다."),
    ADMIN_OPERATION_CONFLICT("AI_ADMIN_OPERATION_CONFLICT", "이미 다른 AI 관리자 작업에 사용된 operationId입니다."),
    RUNTIME_CONFIG_NOT_FOUND("AI_RUNTIME_CONFIG_NOT_FOUND", "AI 설정 버전을 찾을 수 없습니다."),
    INVALID_RUNTIME_CONFIG("AI_INVALID_RUNTIME_CONFIG", "AI 모델 설정이 올바르지 않습니다."),
}
