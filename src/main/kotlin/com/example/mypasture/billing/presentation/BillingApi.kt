package com.example.mypasture.billing.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.billing.application.*
import com.example.mypasture.billing.domain.BillingStore
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** 앱은 서버가 발급한 이 값을 스토어 구매 옵션의 계정 식별자로 전달합니다. */
data class BillingAccountResponse(val appAccountToken: UUID, val obfuscatedAccountId: String)
/** toString에 민감한 구매 증거가 자동 포함되지 않는 요청 모델입니다. */
class VerifyPurchaseRequest(val requestId: UUID, val store: BillingStore,
    @field:NotBlank @field:Size(max = 200) val productId: String,
    @field:NotBlank @field:Size(max = 8192) val proof: String)

/** 구매 검증/복원과 권한 조회의 OpenAPI 계약입니다. 사용자 ID나 지급 수량을 입력받지 않습니다. */
@Tag(name = "결제", description = "인증 사용자의 구매 검증, 복원 상태와 권한을 조회합니다.")
interface BillingApi {
    @Operation(summary = "스토어 계정 식별자 조회", description = "로그인한 사용자에게 연결된 스토어 구매 계정 식별자를 반환합니다.")
    @GetMapping("/account") fun account(@AuthenticationPrincipal principal: JwtPrincipal): BillingAccountResponse
    @Operation(summary = "구매 검증 요청", description = "구매 증거를 영속 검증 작업으로 접수하고 현재 처리 상태를 반환합니다.")
    @PostMapping("/verifications") @ResponseStatus(HttpStatus.ACCEPTED)
    fun verify(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: VerifyPurchaseRequest): BillingVerificationView
    @Operation(summary = "구매 검증 상태 조회", description = "본인이 접수한 구매 검증 작업의 상태를 조회합니다.")
    @GetMapping("/verifications/{id}") fun status(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "구매 검증 작업 UUID.") @PathVariable id: UUID): BillingVerificationView
    @Operation(summary = "내 구매 권한 조회", description = "검증된 구매를 바탕으로 현재 구독과 이용권 권한을 반환합니다.")
    @GetMapping("/entitlements") fun entitlements(@AuthenticationPrincipal principal: JwtPrincipal): BillingEntitlements
}

/** 인증 사용자의 구매 검증 요청을 영속 큐에 전달합니다. */
@RestController
@RequestMapping("/api/v1/billing")
class BillingController(private val service: BillingVerificationService) : BillingApi {
    override fun account(principal: JwtPrincipal): BillingAccountResponse = service.accountToken(principal.userId).let { BillingAccountResponse(it, it.toString()) }
    override fun verify(principal: JwtPrincipal, request: VerifyPurchaseRequest) = service.enqueue(principal.userId, request.requestId, request.store, request.productId, request.proof)
    override fun status(principal: JwtPrincipal, id: UUID) = service.status(principal.userId, id)
    override fun entitlements(principal: JwtPrincipal) = service.entitlements(principal.userId)
}

/** 서명된 알림 원문을 로그용 문자열로 펼치지 않습니다. */
class AppleNotificationRequest(@field:NotBlank @field:Size(max = 65536) val signedPayload: String)
/** Base64로 전달된 구매 토큰을 민감정보로 취급합니다. */
class GoogleNotificationMessage(@field:NotBlank @field:Size(max = 200) val messageId: String,
    @field:NotBlank @field:Size(max = 32768) val data: String)
/** Pub/Sub push 메시지 본문을 검증 계층에 전달합니다. */
class GoogleNotificationRequest(@field:Valid val message: GoogleNotificationMessage)

/** 웹훅은 앱 인증 대신 Apple 서명 또는 Pub/Sub OIDC 검증을 반드시 통과해야 합니다. */
@Tag(name = "결제 알림", description = "서명과 OIDC를 검증한 스토어 알림을 재검증 작업으로 접수합니다.")
interface BillingWebhookApi {
    @Operation(summary = "Apple 구매 알림 접수", description = "서명된 Apple 알림을 확인하고 구매 재검증 작업으로 접수합니다.")
    @PostMapping("/apple") @ResponseStatus(HttpStatus.ACCEPTED)
    fun apple(@Valid @RequestBody request: AppleNotificationRequest)
    @Operation(summary = "Google 구매 알림 접수", description = "Pub/Sub OIDC 인증을 확인하고 구매 재검증 작업으로 접수합니다.")
    @PostMapping("/google") @ResponseStatus(HttpStatus.ACCEPTED)
    fun google(@Parameter(description = "Pub/Sub push OIDC Bearer 토큰.") @RequestHeader("Authorization") authorization: String, @Valid @RequestBody request: GoogleNotificationRequest)
}

/** HTTP 성공은 알림 재검증 작업의 커밋 성공을 의미하며 구매 권한 지급 성공과 구분됩니다. */
@RestController
@RequestMapping("/api/v1/billing/webhooks")
class BillingWebhookController(private val service: BillingNotificationService) : BillingWebhookApi {
    override fun apple(request: AppleNotificationRequest) { service.apple(request.signedPayload) }
    override fun google(authorization: String, request: GoogleNotificationRequest) { service.google(authorization, request.message.messageId, request.message.data) }
}
