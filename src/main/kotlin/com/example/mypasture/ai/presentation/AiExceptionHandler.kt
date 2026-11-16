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
@RestControllerAdvice(assignableTypes = [AiController::class, AiEmbeddingAdminController::class, AiRuntimeConfigAdminController::class, AiFeedbackAdminController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiExceptionHandler {
    @ExceptionHandler(com.example.mypasture.ai.domain.AiFeedbackInvalidException::class) @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidFeedback(exception: com.example.mypasture.ai.domain.AiFeedbackInvalidException, request: HttpServletRequest) =
        AiErrorCode.INVALID_FEEDBACK.toResponse(request, exception.message ?: AiErrorCode.INVALID_FEEDBACK.defaultMessage)
    @ExceptionHandler(com.example.mypasture.ai.domain.AiFeedbackTargetNotFoundException::class) @ResponseStatus(HttpStatus.NOT_FOUND)
    fun feedbackNotFound(exception: com.example.mypasture.ai.domain.AiFeedbackTargetNotFoundException, request: HttpServletRequest) =
        AiErrorCode.FEEDBACK_TARGET_NOT_FOUND.toResponse(request, exception.message ?: AiErrorCode.FEEDBACK_TARGET_NOT_FOUND.defaultMessage)
    @ExceptionHandler(com.example.mypasture.ai.domain.AiFeedbackRegenerationConflictException::class) @ResponseStatus(HttpStatus.CONFLICT)
    fun feedbackConflict(exception: com.example.mypasture.ai.domain.AiFeedbackRegenerationConflictException, request: HttpServletRequest) =
        AiErrorCode.FEEDBACK_REGENERATION_CONFLICT.toResponse(request, exception.message ?: AiErrorCode.FEEDBACK_REGENERATION_CONFLICT.defaultMessage)
    @ExceptionHandler(com.example.mypasture.ai.domain.AiFeedbackRegenerationUnavailableException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun feedbackUnavailable(exception: com.example.mypasture.ai.domain.AiFeedbackRegenerationUnavailableException, request: HttpServletRequest) =
        AiErrorCode.FEEDBACK_REGENERATION_UNAVAILABLE.toResponse(request, exception.message ?: AiErrorCode.FEEDBACK_REGENERATION_UNAVAILABLE.defaultMessage)
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
    @ExceptionHandler(AiQuestionOutOfScopeException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun questionOutOfScope(exception: AiQuestionOutOfScopeException, request: HttpServletRequest) =
        AiErrorCode.QUESTION_OUT_OF_SCOPE.toResponse(request, exception.message ?: AiErrorCode.QUESTION_OUT_OF_SCOPE.defaultMessage)
    @ExceptionHandler(AiQuestionScopeUnclearException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun questionScopeUnclear(exception: AiQuestionScopeUnclearException, request: HttpServletRequest) =
        AiErrorCode.QUESTION_SCOPE_UNCLEAR.toResponse(request, exception.message ?: AiErrorCode.QUESTION_SCOPE_UNCLEAR.defaultMessage)
    @ExceptionHandler(AiGroundingNotFoundException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun groundingNotFound(exception: AiGroundingNotFoundException, request: HttpServletRequest) =
        AiErrorCode.GROUNDING_NOT_FOUND.toResponse(request, exception.message ?: AiErrorCode.GROUNDING_NOT_FOUND.defaultMessage)
    @ExceptionHandler(AiPassageRangeException::class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    fun invalidPassageRange(exception: AiPassageRangeException, request: HttpServletRequest) =
        AiErrorCode.INVALID_PASSAGE_RANGE.toResponse(request, exception.message ?: AiErrorCode.INVALID_PASSAGE_RANGE.defaultMessage)
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
    @ExceptionHandler(com.example.mypasture.ai.domain.AiIdempotencyConflictException::class) @ResponseStatus(HttpStatus.CONFLICT)
    fun idempotencyConflict(exception: com.example.mypasture.ai.domain.AiIdempotencyConflictException, request: HttpServletRequest) =
        AiErrorCode.IDEMPOTENCY_CONFLICT.toResponse(request, exception.message ?: AiErrorCode.IDEMPOTENCY_CONFLICT.defaultMessage)
    @ExceptionHandler(com.example.mypasture.ai.domain.AiRequestInProgressException::class) @ResponseStatus(HttpStatus.CONFLICT)
    fun requestInProgress(exception: com.example.mypasture.ai.domain.AiRequestInProgressException, request: HttpServletRequest) =
        AiErrorCode.REQUEST_IN_PROGRESS.toResponse(request, exception.message ?: AiErrorCode.REQUEST_IN_PROGRESS.defaultMessage)
    @ExceptionHandler(com.example.mypasture.ai.domain.AiRequestPreviouslyFailedException::class) @ResponseStatus(HttpStatus.CONFLICT)
    fun requestFailed(exception: com.example.mypasture.ai.domain.AiRequestPreviouslyFailedException, request: HttpServletRequest) =
        AiErrorCode.REQUEST_FAILED.toResponse(request, exception.message ?: AiErrorCode.REQUEST_FAILED.defaultMessage)
    @ExceptionHandler(com.example.mypasture.ai.domain.AiRequestResultExpiredException::class) @ResponseStatus(HttpStatus.GONE)
    fun requestExpired(exception: com.example.mypasture.ai.domain.AiRequestResultExpiredException, request: HttpServletRequest) =
        AiErrorCode.REQUEST_RESULT_EXPIRED.toResponse(request, exception.message ?: AiErrorCode.REQUEST_RESULT_EXPIRED.defaultMessage)
    @ExceptionHandler(com.example.mypasture.ai.domain.AiRequestNotFoundException::class) @ResponseStatus(HttpStatus.NOT_FOUND)
    fun requestNotFound(exception: com.example.mypasture.ai.domain.AiRequestNotFoundException, request: HttpServletRequest) =
        AiErrorCode.REQUEST_NOT_FOUND.toResponse(request, exception.message ?: AiErrorCode.REQUEST_NOT_FOUND.defaultMessage)
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
    QUESTION_OUT_OF_SCOPE("AI_QUESTION_OUT_OF_SCOPE", "성경·종교·신앙 상담에 관한 질문을 입력해 주세요."),
    QUESTION_SCOPE_UNCLEAR("AI_QUESTION_SCOPE_UNCLEAR", "원하는 도움을 조금 더 구체적으로 적어 주세요."),
    GROUNDING_NOT_FOUND("AI_GROUNDING_NOT_FOUND", "No relevant Bible verses found"),
    INVALID_PASSAGE_RANGE("AI_INVALID_PASSAGE_RANGE", "한 장 안에서 최대 10절까지의 말씀 범위를 입력해 주세요."),
    EVALUATION_REJECTED("AI_EVALUATION_REJECTED", "AI 답변이 품질 검증을 통과하지 못했습니다."),
    SAFETY_ESCALATION("AI_SAFETY_ESCALATION", "즉시 도움을 요청해 주세요."),
    PROVIDER_UNAVAILABLE("AI_PROVIDER_UNAVAILABLE", "AI service unavailable"),
    CONVERSATION_UNAVAILABLE("AI_CONVERSATION_UNAVAILABLE", "AI 대화를 찾을 수 없거나 이미 만료되었습니다."),
    IDEMPOTENCY_CONFLICT("AI_IDEMPOTENCY_CONFLICT", "같은 요청 ID를 다른 질문에 사용할 수 없습니다."),
    REQUEST_IN_PROGRESS("AI_REQUEST_IN_PROGRESS", "기존 AI 요청을 처리하고 있습니다."),
    REQUEST_FAILED("AI_REQUEST_FAILED", "이 AI 요청은 이전에 실패했습니다."),
    REQUEST_RESULT_EXPIRED("AI_REQUEST_RESULT_EXPIRED", "저장된 AI 요청 결과가 만료되었습니다."),
    REQUEST_NOT_FOUND("AI_REQUEST_NOT_FOUND", "AI 요청을 찾을 수 없습니다."),
    EMBEDDING_INDEX_UNAVAILABLE("AI_EMBEDDING_INDEX_UNAVAILABLE", "Embedding index unavailable"),
    STREAM_FAILED("AI_STREAM_FAILED", "AI streaming 요청에 실패했습니다."),
    ADMIN_OPERATION_CONFLICT("AI_ADMIN_OPERATION_CONFLICT", "이미 다른 AI 관리자 작업에 사용된 operationId입니다."),
    RUNTIME_CONFIG_NOT_FOUND("AI_RUNTIME_CONFIG_NOT_FOUND", "AI 설정 버전을 찾을 수 없습니다."),
    INVALID_RUNTIME_CONFIG("AI_INVALID_RUNTIME_CONFIG", "AI 모델 설정이 올바르지 않습니다."),
    INVALID_FEEDBACK("AI_INVALID_FEEDBACK", "AI 답변 평가가 올바르지 않습니다."),
    FEEDBACK_TARGET_NOT_FOUND("AI_FEEDBACK_TARGET_NOT_FOUND", "피드백 대상 AI 답변을 찾을 수 없습니다."),
    FEEDBACK_REGENERATION_CONFLICT("AI_FEEDBACK_REGENERATION_CONFLICT", "개선 답변을 이미 생성하고 있습니다."),
    FEEDBACK_REGENERATION_UNAVAILABLE("AI_FEEDBACK_REGENERATION_UNAVAILABLE", "이 답변은 다시 생성할 수 없습니다."),
}
