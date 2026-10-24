package com.example.mypasture.quiet_time.presentation

import com.example.mypasture.auth.infrastructure.JwtPrincipal
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionCategory
import io.swagger.v3.oas.annotations.tags.Tag
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.*
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

/** Swagger/OpenAPI 문서화를 controller 구현과 분리하기 위한 큐티목장 API contract입니다. */
@Tag(name = "QT 모임", description = "말씀 선택, 질문 생성과 QT 모임 참여·기록을 처리합니다.")
interface QuietTimeApi {
    @Operation(summary = "QT 추천 말씀 조회", description = "QT 질문을 만들기 위한 추천 말씀 목록을 조회합니다.")
    @GetMapping("/passages/recommendations")
    fun recommendedPassages(@Parameter(description = "추천 말씀의 주제 필터.") @RequestParam(required = false) theme: String?, @Parameter(description = "처음 QT를 하는 사용자에게 적합한 말씀만 조회할지 여부.") @RequestParam(defaultValue = "true") beginnerOnly: Boolean, @Parameter(description = "한 번에 반환할 최대 항목 수.") @RequestParam(defaultValue = "5") size: Int): List<QuietTimePassageResponse>

    @Operation(summary = "QT 말씀 선택", description = "로그인한 사용자가 선택한 성경 말씀을 QT 질문용 구절로 등록합니다.")
    @PostMapping("/passages/selected")
    fun selectPassage(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: SelectQuietTimePassageRequest): QuietTimePassageResponse

    @Operation(summary = "선택 말씀 질문 생성 요청", description = "선택한 말씀의 QT 질문 생성을 요청합니다. 동일 조건의 생성 결과는 재사용합니다. 하루에 새 질문 5개, 실패한 호출을 포함한 생성 시도 10회까지 허용하며 각 한도는 429로 응답합니다.")
    @PostMapping("/passages/{passageId}/question-generations")
    suspend fun generateSelectedQuestions(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "선택한 QT 말씀 UUID.") @PathVariable passageId: UUID,
        @Valid @RequestBody request: GenerateQuietTimeQuestionsRequest,
    ): QuietTimeGenerationResponse

    @Operation(summary = "선택 말씀 질문 생성 상태", description = "선택한 말씀과 질문 스타일의 생성 상태 또는 이미 생성된 질문을 조회합니다.")
    @GetMapping("/passages/{passageId}/question-generations")
    fun selectedQuestionStatus(
        @Parameter(description = "선택한 QT 말씀 UUID.") @PathVariable passageId: UUID,
        @Parameter(description = "QT 질문 스타일. 생략하면 BALANCED입니다.") @RequestParam(defaultValue = "BALANCED") style: com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle,
    ): QuietTimeGenerationResponse

    @Operation(summary = "QT 말씀 주제 조회", description = "선택한 말씀에 연결된 질문 주제와 분류 정보를 조회합니다.")
    @GetMapping("/passages/{passageId}/themes")
    fun passageThemes(@Parameter(description = "선택한 QT 말씀 UUID.") @PathVariable passageId: UUID): List<QuietTimePassageThemeResponse>

    @Operation(summary = "QT 질문 미리보기", description = "선택한 말씀으로 만들 질문 구성을 미리 조회합니다.")
    @PostMapping("/passages/{passageId}/question-preview")
    fun previewQuestionSet(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "선택한 QT 말씀 UUID.") @PathVariable passageId: UUID,
        @Valid @RequestBody request: PreviewQuietTimeQuestionSetRequest,
    ): QuietTimeQuestionSetResponse

    @Operation(summary = "고정 QT 목장 생성", description = "로그인한 사용자가 목자로서 QT 목장을 만듭니다.")
    @PostMapping("/pastures")
    fun createPasture(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: CreateQuietTimePastureRequest): QuietTimePastureResponse

    @Operation(summary = "내가 관리하는 목장 목록", description = "로그인한 사용자가 목자로 관리하는 QT 목장을 조회합니다.")
    @GetMapping("/pastures/mine")
    fun managedPastures(@AuthenticationPrincipal principal: JwtPrincipal): List<QuietTimePastureResponse>

    @Operation(summary = "목장 QT 모임 생성", description = "목자가 자신이 관리하는 목장에 일회성 QT 모임을 만듭니다.")
    @PostMapping("/pastures/{pastureId}/sessions")
    fun createSession(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "목자가 관리하는 QT 목장 UUID.") @PathVariable pastureId: UUID, @Valid @RequestBody request: CreateQuietTimeSessionRequest): QuietTimeSessionResponse

    @Operation(summary = "일회성 QT 모임 생성", description = "고정 목장 없이 독립적인 일회성 QT 모임을 생성합니다.")
    @PostMapping("/sessions/standalone")
    fun createStandaloneSession(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: CreateQuietTimeSessionRequest): QuietTimeSessionResponse

    @Operation(summary = "QT 모임 말씀 변경", description = "모임 목자가 지정 모임의 선택 말씀을 변경합니다.")
    @PutMapping("/sessions/{sessionId}/passage")
    fun changeSessionPassage(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID,
        @Valid @RequestBody request: ChangeQuietTimePassageRequest,
    ): QuietTimeSessionResponse

    @Operation(summary = "오프라인 코드로 모임 조회", description = "오프라인 참여 코드에 해당하는 QT 모임 정보를 조회합니다.")
    @GetMapping("/offline/{offlineCode}")
    fun offlineSession(@Parameter(description = "오프라인 QT 모임 참여 코드.") @PathVariable offlineCode: String): QuietTimeSessionResponse

    @Operation(summary = "QT 참여 코드 확인", description = "입력한 초대 코드를 검증하고 참여 대상 모임을 반환합니다.")
    @PostMapping("/join-codes/resolve")
    fun resolveJoinCode(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: ResolveQuietTimeJoinCodeRequest): QuietTimeSessionResponse

    @Operation(summary = "오프라인 QT 모임 참여", description = "코드로 찾은 모임에 로그인한 사용자가 목원으로 참여합니다.")
    @PostMapping("/offline/{offlineCode}/join")
    fun joinOfflineSession(@Parameter(description = "오프라인 QT 모임 참여 코드.") @PathVariable offlineCode: String, @AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: JoinQuietTimeSessionRequest): JoinQuietTimeSessionResponse

    @Operation(summary = "내 QT 묵상 저장", description = "본인의 QT 모임 묵상 내용을 저장합니다.")
    @PutMapping("/sessions/{sessionId}/reflection")
    fun saveReflection(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID, @Valid @RequestBody request: SaveQuietTimeReflectionRequest): QuietTimeReflectionResponse

    @Operation(summary = "내 QT 묵상 조회", description = "해당 모임에 작성한 본인 묵상을 조회합니다.")
    @GetMapping("/sessions/{sessionId}/reflection")
    fun myReflection(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID): QuietTimeReflectionResponse?

    @Operation(summary = "공유된 QT 묵상 조회", description = "참여자가 공유한 묵상만 모임 권한에 맞춰 조회합니다.")
    @GetMapping("/sessions/{sessionId}/reflections/shared")
    fun sharedReflections(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID): List<QuietTimeReflectionResponse>

    @Operation(summary = "QT 질문 답변 저장", description = "지정 질문에 대한 본인 답변을 저장합니다.")
    @PutMapping("/sessions/{sessionId}/answers/{category}/{questionIndex}")
    fun saveQuestionAnswer(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID,
        @Parameter(description = "답변할 QT 질문 범주.") @PathVariable category: QuietTimeQuestionCategory,
        @Parameter(description = "범주 안에서 답변할 질문의 순번.") @PathVariable questionIndex: Int,
        @Valid @RequestBody request: SaveQuietTimeQuestionAnswerRequest,
    ): QuietTimeQuestionAnswerResponse

    @Operation(summary = "QT 질문 답변 조회", description = "모임에서 볼 수 있는 질문 답변을 권한 범위에 맞춰 조회합니다.")
    @GetMapping("/sessions/{sessionId}/answers")
    fun questionAnswers(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID,
    ): List<QuietTimeQuestionAnswerResponse>

    @Operation(summary = "QT 답변 실시간 구독", description = "모임의 질문 답변 변경을 SSE로 전송합니다. 연결이 끊기면 일반 조회로 최신 상태를 다시 확인할 수 있습니다.")
    @GetMapping("/sessions/{sessionId}/answers/stream", produces = ["text/event-stream;charset=UTF-8"])
    fun streamQuestionAnswers(
        @AuthenticationPrincipal principal: JwtPrincipal,
        @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID,
    ): SseEmitter

    @Operation(summary = "QT 참여 QR 재발급", description = "모임 목자가 기존 QR을 무효화하고 새 참여 QR을 발급합니다.")
    @PostMapping("/sessions/{sessionId}/qr/reissue")
    fun reissueQr(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID, @Valid @RequestBody request: ReissueQuietTimeQrRequest): QuietTimeSessionResponse

    @Operation(summary = "QT 참여 QR 무효화", description = "모임 목자가 현재 QR 초대를 무효화합니다.")
    @DeleteMapping("/sessions/{sessionId}/qr") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revokeQr(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID)

    @Operation(summary = "QT 모임 종료", description = "모임 목자가 지정 QT 모임을 종료합니다.")
    @PostMapping("/sessions/{sessionId}/close") @ResponseStatus(HttpStatus.NO_CONTENT)
    fun closeSession(@AuthenticationPrincipal principal: JwtPrincipal, @Parameter(description = "조회하거나 변경할 QT 모임 UUID.") @PathVariable sessionId: UUID)
}
