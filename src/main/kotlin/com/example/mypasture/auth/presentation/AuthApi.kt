package com.example.mypasture.auth.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.auth.presentation.dto.DeleteAccountRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetConfirmRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetRequest
import com.example.mypasture.auth.presentation.dto.PasswordResetRequestedResponse
import com.example.mypasture.auth.presentation.dto.SocialLoginRequest
import com.example.mypasture.auth.presentation.dto.UserDto
import com.example.mypasture.auth.presentation.dto.UserLoginRequest
import com.example.mypasture.auth.presentation.dto.UserLoginResponse
import com.example.mypasture.auth.presentation.dto.UserSignupRequest
import com.example.mypasture.auth.presentation.dto.UserSignupResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 인증 API contract입니다. */
interface AuthApi {
    @GetMapping("/health")
    fun health(): Map<String, String>

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: UserSignupRequest): UserSignupResponse

    @PostMapping("/login")
    fun login(@Valid @RequestBody request: UserLoginRequest): UserLoginResponse

    @PostMapping("/social-login")
    fun socialLogin(@Valid @RequestBody request: SocialLoginRequest): UserLoginResponse

    @PostMapping("/password-reset/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun requestPasswordReset(@Valid @RequestBody request: PasswordResetRequest): PasswordResetRequestedResponse

    @PostMapping("/password-reset/confirm")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun confirmPasswordReset(@Valid @RequestBody request: PasswordResetConfirmRequest)

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal principal: JwtPrincipal): UserDto

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMe(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Valid @RequestBody request: DeleteAccountRequest,
    )
}
