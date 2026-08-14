package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import java.util.UUID

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 큐티목장 API contract입니다. */
interface QuietTimeApi {
    @GetMapping("/passages/recommendations")
    fun recommendedPassages(@RequestParam(required = false) theme: String?, @RequestParam(defaultValue = "true") beginnerOnly: Boolean, @RequestParam(defaultValue = "5") size: Int): List<QuietTimePassageResponse>

    @PostMapping("/pastures")
    fun createPasture(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: CreateQuietTimePastureRequest): QuietTimePastureResponse

    @PostMapping("/pastures/{pastureId}/sessions")
    fun createSession(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable pastureId: UUID, @Valid @RequestBody request: CreateQuietTimeSessionRequest): QuietTimeSessionResponse

    @GetMapping("/offline/{offlineCode}")
    fun offlineSession(@PathVariable offlineCode: String): QuietTimeSessionResponse

    @PostMapping("/offline/{offlineCode}/join")
    fun joinOfflineSession(@PathVariable offlineCode: String, @AuthenticationPrincipal principal: JwtPrincipal?, @Valid @RequestBody request: JoinQuietTimeSessionRequest): JoinQuietTimeSessionResponse

    @PutMapping("/sessions/{sessionId}/reflection")
    fun saveReflection(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable sessionId: UUID, @Valid @RequestBody request: SaveQuietTimeReflectionRequest): QuietTimeReflectionResponse

    @GetMapping("/sessions/{sessionId}/reflection")
    fun myReflection(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable sessionId: UUID): QuietTimeReflectionResponse?

    @GetMapping("/sessions/{sessionId}/reflections/shared")
    fun sharedReflections(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable sessionId: UUID): List<QuietTimeReflectionResponse>

    @PostMapping("/sessions/{sessionId}/qr/reissue")
    fun reissueQr(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable sessionId: UUID, @Valid @RequestBody request: ReissueQuietTimeQrRequest): QuietTimeSessionResponse

    @DeleteMapping("/sessions/{sessionId}/qr") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeQr(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable sessionId: UUID)

    @PostMapping("/sessions/{sessionId}/close") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun closeSession(@AuthenticationPrincipal principal: JwtPrincipal, @PathVariable sessionId: UUID)
}
