package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.AccountDeletionConfirmationException
import com.example.mypasture.auth.application.AccountDeletionReauthenticationException
import com.example.mypasture.auth.application.EmailAlreadyRegisteredException
import com.example.mypasture.auth.application.EmailVerificationRequiredException
import com.example.mypasture.auth.application.InvalidEmailVerificationCodeException
import com.example.mypasture.auth.application.InvalidDisplayNameException
import com.example.mypasture.auth.application.NicknameAlreadyUsedException
import com.example.mypasture.auth.application.NicknamePoolExhaustedException
import com.example.mypasture.auth.application.InvalidCredentialsException
import com.example.mypasture.auth.application.InvalidPasswordResetTokenException
import com.example.mypasture.auth.application.InvalidRefreshTokenException
import com.example.mypasture.auth.application.InvalidSocialLoginChallengeException
import com.example.mypasture.auth.application.MissingSocialLoginChallengeException
import com.example.mypasture.auth.application.PasswordReuseNotAllowedException
import com.example.mypasture.auth.application.SocialAccountLinkRequiredException
import com.example.mypasture.auth.application.SocialEmailNotVerifiedException
import com.example.mypasture.auth.application.UnsupportedSocialProviderException
import com.example.mypasture.auth.infrastructure.InvalidSocialTokenException
import com.example.mypasture.auth.infrastructure.MissingSocialTokenException
import com.example.mypasture.auth.infrastructure.SocialOAuthProviderMisconfiguredException
import com.example.mypasture.common.error.ErrorCode
import com.example.mypasture.common.error.ApiErrorResponse
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 인증 도메인 예외를 인증 도메인이 소유한 코드와 공통 HTTP 오류 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [AuthController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthExceptionHandler {
    @ExceptionHandler(InvalidWebSessionOriginException::class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    fun handleInvalidWebSessionOrigin(request: HttpServletRequest) =
        AuthErrorCode.WEB_SESSION_ORIGIN_FORBIDDEN.toResponse(request)

    /** 조회 후 동시 저장 경합에서도 DB 고유 제약을 안정적인 중복 오류로 변환합니다. */
    @ExceptionHandler(DataIntegrityViolationException::class)
    fun handleNicknameConstraint(error: DataIntegrityViolationException, request: HttpServletRequest): ResponseEntity<ApiErrorResponse> {
        val details = generateSequence(error as Throwable) { it.cause }.mapNotNull { it.message }.joinToString(" ")
        val code = when {
            details.contains("ux_users_nickname_key", ignoreCase = true) -> AuthErrorCode.NICKNAME_ALREADY_USED
            details.contains("ux_users_email_active", ignoreCase = true) -> AuthErrorCode.EMAIL_ALREADY_REGISTERED
            else -> throw error
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(code.toResponse(request))
    }

    @ExceptionHandler(NicknameAlreadyUsedException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleNicknameAlreadyUsed(request: HttpServletRequest) = AuthErrorCode.NICKNAME_ALREADY_USED.toResponse(request)

    @ExceptionHandler(NicknamePoolExhaustedException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleNicknamePoolExhausted(request: HttpServletRequest) = AuthErrorCode.NICKNAME_POOL_EXHAUSTED.toResponse(request)

    @ExceptionHandler(InvalidEmailVerificationCodeException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidEmailVerificationCode(request: HttpServletRequest) =
        AuthErrorCode.INVALID_EMAIL_VERIFICATION_CODE.toResponse(request)

    @ExceptionHandler(EmailVerificationRequiredException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleEmailVerificationRequired(request: HttpServletRequest) =
        AuthErrorCode.EMAIL_VERIFICATION_REQUIRED.toResponse(request)

    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleEmailAlreadyRegistered(request: HttpServletRequest) =
        AuthErrorCode.EMAIL_ALREADY_REGISTERED.toResponse(request)

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidCredentials(request: HttpServletRequest) =
        AuthErrorCode.INVALID_CREDENTIALS.toResponse(request)

    @ExceptionHandler(UnsupportedSocialProviderException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleUnsupportedSocialProvider(request: HttpServletRequest) =
        AuthErrorCode.UNSUPPORTED_SOCIAL_PROVIDER.toResponse(request)

    @ExceptionHandler(MissingSocialTokenException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMissingSocialToken(request: HttpServletRequest) =
        AuthErrorCode.MISSING_SOCIAL_TOKEN.toResponse(request)

    @ExceptionHandler(InvalidSocialTokenException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidSocialToken(request: HttpServletRequest) =
        AuthErrorCode.INVALID_SOCIAL_TOKEN.toResponse(request)

    @ExceptionHandler(SocialEmailNotVerifiedException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleSocialEmailNotVerified(request: HttpServletRequest) =
        AuthErrorCode.SOCIAL_EMAIL_NOT_VERIFIED.toResponse(request)

    @ExceptionHandler(SocialAccountLinkRequiredException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleSocialAccountLinkRequired(request: HttpServletRequest) =
        AuthErrorCode.SOCIAL_ACCOUNT_LINK_REQUIRED.toResponse(request)

    @ExceptionHandler(InvalidRefreshTokenException::class)
    fun handleInvalidRefreshToken(request: HttpServletRequest) =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .apply {
                if (request.requestURI == "/api/v1/auth/web/refresh") {
                    header(HttpHeaders.SET_COOKIE, WebSessionCookie.cleared())
                    header(HttpHeaders.CACHE_CONTROL, "no-store")
                }
            }
            .body(AuthErrorCode.INVALID_REFRESH_TOKEN.toResponse(request))

    @ExceptionHandler(MissingSocialLoginChallengeException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMissingSocialLoginChallenge(request: HttpServletRequest) =
        AuthErrorCode.MISSING_SOCIAL_CHALLENGE.toResponse(request)

    @ExceptionHandler(InvalidSocialLoginChallengeException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidSocialLoginChallenge(request: HttpServletRequest) =
        AuthErrorCode.INVALID_SOCIAL_CHALLENGE.toResponse(request)

    @ExceptionHandler(SocialOAuthProviderMisconfiguredException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleSocialOAuthProviderMisconfigured(request: HttpServletRequest) =
        AuthErrorCode.SOCIAL_PROVIDER_MISCONFIGURED.toResponse(request)

    @ExceptionHandler(InvalidPasswordResetTokenException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidPasswordResetToken(request: HttpServletRequest) =
        AuthErrorCode.INVALID_PASSWORD_RESET_TOKEN.toResponse(request)

    @ExceptionHandler(PasswordReuseNotAllowedException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handlePasswordReuseNotAllowed(request: HttpServletRequest) =
        AuthErrorCode.PASSWORD_REUSE_NOT_ALLOWED.toResponse(request)

    @ExceptionHandler(AccountDeletionConfirmationException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleAccountDeletionConfirmation(request: HttpServletRequest) =
        AuthErrorCode.INVALID_DELETION_CONFIRMATION.toResponse(request)

    @ExceptionHandler(AccountDeletionReauthenticationException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleAccountDeletionReauthentication(request: HttpServletRequest) =
        AuthErrorCode.REAUTHENTICATION_FAILED.toResponse(request)

    @ExceptionHandler(InvalidDisplayNameException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidDisplayName(request: HttpServletRequest) =
        AuthErrorCode.INVALID_DISPLAY_NAME.toResponse(request)
}

/** 인증 도메인이 외부 클라이언트에 제공하는 안정적인 오류 코드와 기본 메시지입니다. */
enum class AuthErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : ErrorCode {
    INVALID_EMAIL_VERIFICATION_CODE("INVALID_EMAIL_VERIFICATION_CODE", "인증번호가 만료되었거나 올바르지 않습니다."),
    EMAIL_VERIFICATION_REQUIRED("EMAIL_VERIFICATION_REQUIRED", "이메일 인증을 완료한 뒤 회원가입해 주세요."),
    EMAIL_ALREADY_REGISTERED("EMAIL_ALREADY_REGISTERED", "이미 가입된 이메일입니다."),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
    WEB_SESSION_ORIGIN_FORBIDDEN("WEB_SESSION_ORIGIN_FORBIDDEN", "허용되지 않은 웹 출처입니다."),
    UNSUPPORTED_SOCIAL_PROVIDER("UNSUPPORTED_SOCIAL_PROVIDER", "지원하지 않는 소셜 로그인 제공자입니다."),
    MISSING_SOCIAL_TOKEN("MISSING_SOCIAL_TOKEN", "소셜 로그인 토큰이 필요합니다."),
    INVALID_SOCIAL_TOKEN("INVALID_SOCIAL_TOKEN", "소셜 로그인 토큰을 검증할 수 없습니다."),
    SOCIAL_EMAIL_NOT_VERIFIED("SOCIAL_EMAIL_NOT_VERIFIED", "소셜 계정 이메일 인증이 필요합니다."),
    SOCIAL_ACCOUNT_LINK_REQUIRED("SOCIAL_ACCOUNT_LINK_REQUIRED", "기존 계정으로 로그인한 뒤 소셜 계정을 연결해 주세요."),
    INVALID_REFRESH_TOKEN("INVALID_REFRESH_TOKEN", "로그인 세션이 만료되었거나 다시 사용할 수 없습니다."),
    MISSING_SOCIAL_CHALLENGE("MISSING_SOCIAL_CHALLENGE", "소셜 로그인 보안 challenge가 필요합니다."),
    INVALID_SOCIAL_CHALLENGE("INVALID_SOCIAL_CHALLENGE", "소셜 로그인 요청이 만료되었거나 이미 사용되었습니다."),
    SOCIAL_PROVIDER_MISCONFIGURED("SOCIAL_PROVIDER_MISCONFIGURED", "소셜 로그인 제공자 설정이 필요합니다."),
    INVALID_PASSWORD_RESET_TOKEN("INVALID_PASSWORD_RESET_TOKEN", "재설정 링크가 만료되었거나 이미 사용되었습니다."),
    PASSWORD_REUSE_NOT_ALLOWED("PASSWORD_REUSE_NOT_ALLOWED", "기존 비밀번호와 다른 비밀번호를 사용해 주세요."),
    INVALID_DELETION_CONFIRMATION("INVALID_DELETION_CONFIRMATION", "회원탈퇴 확인값이 올바르지 않습니다."),
    REAUTHENTICATION_FAILED("REAUTHENTICATION_FAILED", "회원탈퇴를 위해 현재 비밀번호를 확인해 주세요."),
    INVALID_DISPLAY_NAME("INVALID_DISPLAY_NAME", "닉네임은 공백·특수문자 없이 한글 8자 또는 영문 10자까지 입력해 주세요."),
    NICKNAME_ALREADY_USED("NICKNAME_ALREADY_USED", "이미 사용 중인 닉네임입니다."),
    NICKNAME_POOL_EXHAUSTED("NICKNAME_POOL_EXHAUSTED", "새 닉네임을 만들 수 없습니다. 잠시 후 다시 시도해 주세요."),
}
