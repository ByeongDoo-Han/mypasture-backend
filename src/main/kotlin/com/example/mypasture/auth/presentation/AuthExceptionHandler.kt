package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.application.AccountDeletionConfirmationException
import com.example.mypasture.auth.application.AccountDeletionReauthenticationException
import com.example.mypasture.auth.application.EmailAlreadyRegisteredException
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
import com.example.mypasture.common.error.toResponse
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 인증 도메인 예외를 인증 도메인이 소유한 코드와 공통 HTTP 오류 응답으로 변환합니다. */
@RestControllerAdvice(assignableTypes = [AuthController::class])
@Order(Ordered.HIGHEST_PRECEDENCE)
class AuthExceptionHandler {
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
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidRefreshToken(request: HttpServletRequest) =
        AuthErrorCode.INVALID_REFRESH_TOKEN.toResponse(request)

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
}

/** 인증 도메인이 외부 클라이언트에 제공하는 안정적인 오류 코드와 기본 메시지입니다. */
enum class AuthErrorCode(
    override val code: String,
    override val defaultMessage: String,
) : ErrorCode {
    EMAIL_ALREADY_REGISTERED("EMAIL_ALREADY_REGISTERED", "이미 가입된 이메일입니다."),
    INVALID_CREDENTIALS("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다."),
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
}
