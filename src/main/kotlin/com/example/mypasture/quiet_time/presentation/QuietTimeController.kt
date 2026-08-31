package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.quiet_time.application.QuietTimeService
import com.example.mypasture.quiet_time.application.QuietTimeReflectionService
import com.example.mypasture.quiet_time.application.QuietTimeQuestionAnswerService
import com.example.mypasture.quiet_time.application.QuietTimeQuestionSetService
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionCategory
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** 큐티목장 본문 추천과 기간 제한 QR 세션 API를 제공하는 controller입니다. */
@RestController
@RequestMapping("/api/v1/quiet-time")
class QuietTimeController(
    private val quietTimeService: QuietTimeService,
    private val reflectionService: QuietTimeReflectionService,
    private val answerService: QuietTimeQuestionAnswerService,
    private val questionSetService: QuietTimeQuestionSetService,
    private val answerStreamRegistry: QuietTimeAnswerStreamRegistry,
) : QuietTimeApi {
    override fun recommendedPassages(
        theme: String?,
        beginnerOnly: Boolean,
        size: Int,
    ): List<QuietTimePassageResponse> =
        quietTimeService.recommendPassages(theme, beginnerOnly, size).map { it.toResponse() }

    override fun passageThemes(passageId: UUID): List<QuietTimePassageThemeResponse> =
        questionSetService.themesForPassage(passageId).map { it.toResponse() }

    override fun previewQuestionSet(
        principal: JwtPrincipal,
        passageId: UUID,
        request: PreviewQuietTimeQuestionSetRequest,
    ): QuietTimeQuestionSetResponse =
        questionSetService.preview(passageId, request.focusThemeCode, request.questionStyle).toResponse()

    override fun createPasture(
        principal: JwtPrincipal,
        request: CreateQuietTimePastureRequest,
    ): QuietTimePastureResponse =
        quietTimeService.createPasture(principal.userId, request.name, request.leaderName).toResponse()

    override fun managedPastures(principal: JwtPrincipal): List<QuietTimePastureResponse> =
        quietTimeService.getManagedPastures(principal.userId).map { it.toResponse() }

    override fun createSession(
        principal: JwtPrincipal,
        pastureId: UUID,
        request: CreateQuietTimeSessionRequest,
    ): QuietTimeSessionResponse =
        quietTimeService.createPastureSession(
            leaderUserId = principal.userId,
            pastureId = pastureId,
            title = request.title,
            passageId = request.passageId,
            theme = request.theme,
            focusThemeCode = request.focusThemeCode,
            questionStyle = request.questionStyle,
            meetingDate = request.meetingDate,
            meetingStartsAt = request.meetingStartsAt,
            meetingEndsAt = request.meetingEndsAt,
            joinStartsAt = request.joinStartsAt,
        ).toResponse()

    override fun createStandaloneSession(
        principal: JwtPrincipal,
        request: CreateQuietTimeSessionRequest,
    ): QuietTimeSessionResponse =
        quietTimeService.createStandaloneSession(
            hostUserId = principal.userId,
            hostDisplayName = principal.displayName,
            title = request.title,
            passageId = request.passageId,
            theme = request.theme,
            focusThemeCode = request.focusThemeCode,
            questionStyle = request.questionStyle,
            meetingDate = request.meetingDate,
            meetingStartsAt = request.meetingStartsAt,
            meetingEndsAt = request.meetingEndsAt,
            joinStartsAt = request.joinStartsAt,
        ).toResponse()

    override fun offlineSession(offlineCode: String): QuietTimeSessionResponse =
        quietTimeService.getOfflineSession(offlineCode).toResponse()

    override fun resolveJoinCode(
        principal: JwtPrincipal,
        request: ResolveQuietTimeJoinCodeRequest,
    ): QuietTimeSessionResponse = quietTimeService.resolveJoinCode(request.joinCode).toResponse()

    override fun joinOfflineSession(
        offlineCode: String,
        principal: JwtPrincipal,
        request: JoinQuietTimeSessionRequest,
    ): JoinQuietTimeSessionResponse =
        quietTimeService.joinOfflineSession(offlineCode, request.displayName, request.deviceId, principal.userId).toResponse()

    override fun saveReflection(principal: JwtPrincipal, sessionId: UUID, request: SaveQuietTimeReflectionRequest) = reflectionService.save(sessionId, principal.userId, request.content, request.sharedWithPasture).toResponse()

    override fun myReflection(principal: JwtPrincipal, sessionId: UUID) = reflectionService.mine(sessionId, principal.userId)?.toResponse()

    override fun sharedReflections(principal: JwtPrincipal, sessionId: UUID) = reflectionService.shared(sessionId, principal.userId).map { it.toResponse() }

    override fun saveQuestionAnswer(
        principal: JwtPrincipal,
        sessionId: UUID,
        category: QuietTimeQuestionCategory,
        questionIndex: Int,
        request: SaveQuietTimeQuestionAnswerRequest,
    ) = answerService.save(sessionId, principal.userId, category, questionIndex, request.content).toResponse()

    override fun questionAnswers(principal: JwtPrincipal, sessionId: UUID) =
        answerService.list(sessionId, principal.userId).map { it.toResponse() }

    override fun streamQuestionAnswers(principal: JwtPrincipal, sessionId: UUID) =
        answerService.requireReadAccess(sessionId, principal.userId).let { answerStreamRegistry.subscribe(sessionId) }

    override fun reissueQr(
        principal: JwtPrincipal,
        sessionId: UUID,
        request: ReissueQuietTimeQrRequest,
    ): QuietTimeSessionResponse =
        quietTimeService.reissueQr(principal.userId, sessionId, request.joinStartsAt).toResponse()

    override fun revokeQr(
        principal: JwtPrincipal,
        sessionId: UUID,
    ) = quietTimeService.revokeQr(principal.userId, sessionId)

    override fun closeSession(
        principal: JwtPrincipal,
        sessionId: UUID,
    ) {
        quietTimeService.closeSession(principal.userId, sessionId)
        answerStreamRegistry.completeSession(sessionId)
    }
}
