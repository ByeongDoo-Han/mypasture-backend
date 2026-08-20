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
import com.example.mypasture.auth.application.SocialEmailNotVerifiedException
import com.example.mypasture.auth.application.SocialAccountLinkRequiredException
import com.example.mypasture.auth.application.UnsupportedSocialProviderException
import com.example.mypasture.auth.infrastructure.InvalidSocialTokenException
import com.example.mypasture.auth.infrastructure.MissingSocialTokenException
import com.example.mypasture.auth.infrastructure.SocialOAuthProviderMisconfiguredException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 인증 도메인 예외를 일관된 HTTP 오류 응답으로 변환하는 controller advice입니다. */
@RestControllerAdvice(assignableTypes = [AuthController::class])
class AuthExceptionHandler {
    @ExceptionHandler(EmailAlreadyRegisteredException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleEmailAlreadyRegistered() = AuthErrorResponse("EMAIL_ALREADY_REGISTERED", "이미 가입된 이메일입니다.")

    @ExceptionHandler(InvalidCredentialsException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidCredentials() = AuthErrorResponse("INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.")

    @ExceptionHandler(UnsupportedSocialProviderException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleUnsupportedSocialProvider() = AuthErrorResponse("UNSUPPORTED_SOCIAL_PROVIDER", "지원하지 않는 소셜 로그인 제공자입니다.")

    @ExceptionHandler(MissingSocialTokenException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMissingSocialToken() = AuthErrorResponse("MISSING_SOCIAL_TOKEN", "소셜 로그인 토큰이 필요합니다.")

    @ExceptionHandler(InvalidSocialTokenException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidSocialToken() = AuthErrorResponse("INVALID_SOCIAL_TOKEN", "소셜 로그인 토큰을 검증할 수 없습니다.")

    @ExceptionHandler(SocialEmailNotVerifiedException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleSocialEmailNotVerified() = AuthErrorResponse("SOCIAL_EMAIL_NOT_VERIFIED", "소셜 계정 이메일 인증이 필요합니다.")

    @ExceptionHandler(SocialAccountLinkRequiredException::class)
    @ResponseStatus(HttpStatus.CONFLICT)
    fun handleSocialAccountLinkRequired() = AuthErrorResponse("SOCIAL_ACCOUNT_LINK_REQUIRED", "기존 계정으로 로그인한 뒤 소셜 계정을 연결해 주세요.")

    @ExceptionHandler(InvalidRefreshTokenException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidRefreshToken() = AuthErrorResponse("INVALID_REFRESH_TOKEN", "로그인 세션이 만료되었거나 다시 사용할 수 없습니다.")

    @ExceptionHandler(MissingSocialLoginChallengeException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleMissingSocialLoginChallenge() = AuthErrorResponse("MISSING_SOCIAL_CHALLENGE", "소셜 로그인 보안 challenge가 필요합니다.")

    @ExceptionHandler(InvalidSocialLoginChallengeException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleInvalidSocialLoginChallenge() = AuthErrorResponse("INVALID_SOCIAL_CHALLENGE", "소셜 로그인 요청이 만료되었거나 이미 사용되었습니다.")

    @ExceptionHandler(SocialOAuthProviderMisconfiguredException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleSocialOAuthProviderMisconfigured() = AuthErrorResponse("SOCIAL_PROVIDER_MISCONFIGURED", "소셜 로그인 제공자 설정이 필요합니다.")

    @ExceptionHandler(InvalidPasswordResetTokenException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidPasswordResetToken() = AuthErrorResponse("INVALID_PASSWORD_RESET_TOKEN", "재설정 링크가 만료되었거나 이미 사용되었습니다.")

    @ExceptionHandler(PasswordReuseNotAllowedException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handlePasswordReuseNotAllowed() = AuthErrorResponse("PASSWORD_REUSE_NOT_ALLOWED", "기존 비밀번호와 다른 비밀번호를 사용해 주세요.")

    @ExceptionHandler(AccountDeletionConfirmationException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleAccountDeletionConfirmation() = AuthErrorResponse("INVALID_DELETION_CONFIRMATION", "회원탈퇴 확인값이 올바르지 않습니다.")

    @ExceptionHandler(AccountDeletionReauthenticationException::class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    fun handleAccountDeletionReauthentication() = AuthErrorResponse("REAUTHENTICATION_FAILED", "회원탈퇴를 위해 현재 비밀번호를 확인해 주세요.")
}

/** 인증 API 에러 응답 DTO입니다. */
data class AuthErrorResponse(
    val code: String,
    val message: String,
)
