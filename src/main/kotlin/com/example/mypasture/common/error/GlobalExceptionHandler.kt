package com.example.mypasture.common.error

import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.validation.BindException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.servlet.resource.NoResourceFoundException

/** 도메인과 무관한 Spring MVC 오류와 예상하지 못한 서버 오류를 처리합니다. */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class GlobalExceptionHandler {
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleMethodArgumentNotValid(
        exception: MethodArgumentNotValidException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        badRequest(
            request,
            exception.bindingResult.fieldErrors.map {
                FieldErrorResponse(it.field, it.defaultMessage ?: CommonErrorCode.INVALID_REQUEST.defaultMessage)
            },
        )

    @ExceptionHandler(BindException::class)
    fun handleBind(
        exception: BindException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        badRequest(
            request,
            exception.bindingResult.fieldErrors.map {
                FieldErrorResponse(it.field, it.defaultMessage ?: CommonErrorCode.INVALID_REQUEST.defaultMessage)
            },
        )

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(
        exception: ConstraintViolationException,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        badRequest(
            request,
            exception.constraintViolations.map {
                FieldErrorResponse(it.propertyPath.toString(), it.message)
            },
        )

    @ExceptionHandler(
        MethodArgumentTypeMismatchException::class,
        MissingServletRequestParameterException::class,
    )
    fun handleInvalidParameter(
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse.of(CommonErrorCode.INVALID_REQUEST, request),
        )

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleMalformedBody(
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse.of(CommonErrorCode.MALFORMED_REQUEST, request),
        )

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleMethodNotAllowed(
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(
            ApiErrorResponse.of(CommonErrorCode.METHOD_NOT_ALLOWED, request),
        )

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleResourceNotFound(
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(
            ApiErrorResponse.of(CommonErrorCode.RESOURCE_NOT_FOUND, request),
        )

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(
        exception: Exception,
        request: HttpServletRequest,
    ): ResponseEntity<ApiErrorResponse> {
        logger.error("Unhandled API exception", exception)
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
            ApiErrorResponse.of(CommonErrorCode.INTERNAL_SERVER_ERROR, request),
        )
    }

    private fun badRequest(
        request: HttpServletRequest,
        fieldErrors: List<FieldErrorResponse>,
    ): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.badRequest().body(
            ApiErrorResponse.of(
                errorCode = CommonErrorCode.INVALID_REQUEST,
                request = request,
                fieldErrors = fieldErrors.sortedBy { it.field },
            ),
        )

    companion object {
        private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }
}
