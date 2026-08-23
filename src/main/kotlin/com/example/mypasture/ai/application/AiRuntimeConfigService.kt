package com.example.mypasture.ai.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.ai.domain.AiModelCatalog
import com.example.mypasture.ai.domain.AiModelDefinition
import com.example.mypasture.ai.domain.AiModelRole
import com.example.mypasture.ai.domain.AiReasoningEffort
import com.example.mypasture.ai.domain.AiRuntimeConfigPointerEntity
import com.example.mypasture.ai.domain.AiRuntimeConfigSnapshot
import com.example.mypasture.ai.domain.AiRuntimeConfigVersionEntity
import com.example.mypasture.ai.domain.AiUseCase
import com.example.mypasture.ai.infrastructure.AiRuntimeConfigPointerRepository
import com.example.mypasture.ai.infrastructure.AiRuntimeConfigVersionRepository
import com.example.mypasture.config.AiProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

class AiAdminOperationConflictException : RuntimeException("Operation id is already used for another AI admin request")
class AiRuntimeConfigNotFoundException : RuntimeException("AI runtime configuration version was not found")

/** 관리자가 제출한 AI 목자 생성·평가 모델 설정입니다. */
data class AiRuntimeConfigCommand(
    val useCase: AiUseCase,
    val generatorModel: String,
    val evaluatorModel: String,
    val generatorReasoningEffort: AiReasoningEffort,
    val evaluatorReasoningEffort: AiReasoningEffort,
    val maxOutputTokens: Int,
    val evaluationMaxOutputTokens: Int,
    val evaluationEnabled: Boolean,
    val evaluationThreshold: Int,
    val maxRevisionCount: Int,
    val reason: String,
)

/** 관리자 화면에 노출할 설정 버전과 활성 여부입니다. */
data class AiRuntimeConfigVersionView(
    val config: AiRuntimeConfigSnapshot,
    val active: Boolean,
    val previousVersionId: UUID?,
    val createdBy: UUID?,
    val reason: String,
    val createdAt: Instant,
)

/** 요청 시작 시 DB의 활성 버전을 읽어 불변 스냅샷으로 제공하고 관리자 변경을 직렬화합니다. */
@Service
class AiRuntimeConfigService(
    private val pointerRepository: AiRuntimeConfigPointerRepository,
    private val versionRepository: AiRuntimeConfigVersionRepository,
    private val auditLogRepository: AdminAuditLogRepository,
    private val properties: AiProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun current(useCase: AiUseCase = AiUseCase.AI_PASTOR): AiRuntimeConfigSnapshot {
        val pointer = pointerRepository.findById(useCase).orElse(null)
        return pointer?.activeVersionId?.let { versionRepository.findById(it).orElse(null)?.snapshot() }
            ?: environmentFallback(useCase)
    }

    fun allowedModels(): List<AiModelDefinition> = AiModelCatalog.models

    @Transactional(readOnly = true)
    fun history(useCase: AiUseCase = AiUseCase.AI_PASTOR): List<AiRuntimeConfigVersionView> {
        val activeId = pointerRepository.findById(useCase).orElse(null)?.activeVersionId
        return versionRepository.findTop20ByUseCaseOrderByVersionNumberDesc(useCase).map {
            AiRuntimeConfigVersionView(
                config = it.snapshot(),
                active = it.id == activeId,
                previousVersionId = it.previousVersionId,
                createdBy = it.createdBy,
                reason = it.reason,
                createdAt = it.createdAt,
            )
        }
    }

    @Transactional
    fun activate(
        adminUserId: UUID,
        operationId: UUID,
        command: AiRuntimeConfigCommand,
    ): AiRuntimeConfigVersionView {
        val normalized = validate(command)
        val fingerprint = fingerprint("ACTIVATE", normalized.canonical())
        existing(operationId, AdminAuditAction.AI_RUNTIME_CONFIG_ACTIVATED, fingerprint)?.let { return view(it) }

        val pointer = lockPointer(normalized.useCase)
        val version = createVersion(
            pointer = pointer,
            command = normalized,
            adminUserId = adminUserId,
            previousVersionId = pointer.activeVersionId,
        )
        pointer.activate(version.id)
        pointerRepository.save(pointer)
        auditLogRepository.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminUserId,
                action = AdminAuditAction.AI_RUNTIME_CONFIG_ACTIVATED,
                targetType = TARGET_TYPE,
                targetId = version.id,
                reason = normalized.reason,
                details = "useCase=${normalized.useCase};version=${version.versionNumber};generator=${version.generatorModel};evaluator=${version.evaluatorModel}",
                requestFingerprint = fingerprint,
                createdAt = Instant.now(clock),
            ),
        )
        return view(version, active = true)
    }

    @Transactional
    fun rollback(
        adminUserId: UUID,
        operationId: UUID,
        targetVersionId: UUID,
        reason: String,
    ): AiRuntimeConfigVersionView {
        val normalizedReason = normalizeReason(reason)
        val fingerprint = fingerprint("ROLLBACK", "$targetVersionId|$normalizedReason")
        existing(operationId, AdminAuditAction.AI_RUNTIME_CONFIG_ROLLED_BACK, fingerprint)?.let { return view(it) }

        val target = versionRepository.findById(targetVersionId)
            .orElseThrow { AiRuntimeConfigNotFoundException() }
        val pointer = lockPointer(target.useCase)
        val command = AiRuntimeConfigCommand(
            useCase = target.useCase,
            generatorModel = target.generatorModel,
            evaluatorModel = target.evaluatorModel,
            generatorReasoningEffort = target.generatorReasoningEffort,
            evaluatorReasoningEffort = target.evaluatorReasoningEffort,
            maxOutputTokens = target.maxOutputTokens,
            evaluationMaxOutputTokens = target.evaluationMaxOutputTokens,
            evaluationEnabled = target.evaluationEnabled,
            evaluationThreshold = target.evaluationThreshold,
            maxRevisionCount = target.maxRevisionCount,
            reason = normalizedReason,
        )
        val version = createVersion(pointer, command, adminUserId, pointer.activeVersionId)
        pointer.activate(version.id)
        pointerRepository.save(pointer)
        auditLogRepository.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminUserId,
                action = AdminAuditAction.AI_RUNTIME_CONFIG_ROLLED_BACK,
                targetType = TARGET_TYPE,
                targetId = version.id,
                reason = normalizedReason,
                details = "useCase=${target.useCase};version=${version.versionNumber};restoredFrom=${target.id}",
                requestFingerprint = fingerprint,
                createdAt = Instant.now(clock),
            ),
        )
        return view(version, active = true)
    }

    private fun lockPointer(useCase: AiUseCase): AiRuntimeConfigPointerEntity =
        pointerRepository.findByUseCaseForUpdate(useCase)
            ?: pointerRepository.saveAndFlush(AiRuntimeConfigPointerEntity(useCase))

    private fun createVersion(
        pointer: AiRuntimeConfigPointerEntity,
        command: AiRuntimeConfigCommand,
        adminUserId: UUID,
        previousVersionId: UUID?,
    ): AiRuntimeConfigVersionEntity = versionRepository.save(
        AiRuntimeConfigVersionEntity(
            useCase = command.useCase,
            versionNumber = pointer.nextVersion(),
            generatorModel = command.generatorModel,
            evaluatorModel = command.evaluatorModel,
            generatorReasoningEffort = command.generatorReasoningEffort,
            evaluatorReasoningEffort = command.evaluatorReasoningEffort,
            maxOutputTokens = command.maxOutputTokens,
            evaluationMaxOutputTokens = command.evaluationMaxOutputTokens,
            evaluationEnabled = command.evaluationEnabled,
            evaluationThreshold = command.evaluationThreshold,
            maxRevisionCount = command.maxRevisionCount,
            previousVersionId = previousVersionId,
            createdBy = adminUserId,
            reason = command.reason,
            createdAt = Instant.now(clock),
        ),
    )

    private fun validate(command: AiRuntimeConfigCommand): AiRuntimeConfigCommand {
        AiModelCatalog.require(command.generatorModel, AiModelRole.GENERATOR)
        AiModelCatalog.require(command.evaluatorModel, AiModelRole.EVALUATOR)
        require(command.maxOutputTokens in 100..2_000) { "maxOutputTokens must be between 100 and 2000." }
        require(command.evaluationMaxOutputTokens in 100..500) { "evaluationMaxOutputTokens must be between 100 and 500." }
        require(command.evaluationThreshold in 1..5) { "evaluationThreshold must be between 1 and 5." }
        require(command.maxRevisionCount in 0..1) { "maxRevisionCount must be between 0 and 1." }
        return command.copy(reason = normalizeReason(command.reason))
    }

    private fun normalizeReason(reason: String): String = reason.trim().also {
        require(it.length in 5..500) { "Reason length must be between 5 and 500." }
    }

    private fun existing(operationId: UUID, action: AdminAuditAction, fingerprint: String): AiRuntimeConfigVersionEntity? =
        auditLogRepository.findByOperationId(operationId)?.let { audit ->
            if (audit.action != action || audit.requestFingerprint != fingerprint || audit.targetType != TARGET_TYPE) {
                throw AiAdminOperationConflictException()
            }
            versionRepository.findById(audit.targetId).orElseThrow { AiRuntimeConfigNotFoundException() }
        }

    private fun view(entity: AiRuntimeConfigVersionEntity, active: Boolean? = null): AiRuntimeConfigVersionView {
        val isActive = active ?: (pointerRepository.findById(entity.useCase).orElse(null)?.activeVersionId == entity.id)
        return AiRuntimeConfigVersionView(
            config = entity.snapshot(),
            active = isActive,
            previousVersionId = entity.previousVersionId,
            createdBy = entity.createdBy,
            reason = entity.reason,
            createdAt = entity.createdAt,
        )
    }

    private fun environmentFallback(useCase: AiUseCase) = AiRuntimeConfigSnapshot(
        id = null,
        useCase = useCase,
        version = 0,
        generatorModel = properties.generatorModel,
        evaluatorModel = properties.evaluatorModel,
        generatorReasoningEffort = reasoning(properties.reasoningEffort),
        evaluatorReasoningEffort = reasoning(properties.evaluatorReasoningEffort),
        maxOutputTokens = properties.maxOutputTokens,
        evaluationMaxOutputTokens = properties.evaluationMaxOutputTokens,
        evaluationEnabled = properties.evaluationEnabled,
        evaluationThreshold = properties.evaluationThreshold,
        maxRevisionCount = properties.maxRevisionCount,
        source = "ENVIRONMENT",
    )

    private fun reasoning(value: String): AiReasoningEffort =
        if (value.equals("minimal", true)) AiReasoningEffort.LOW else AiReasoningEffort.valueOf(value.uppercase())

    private fun fingerprint(action: String, canonical: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$action|$canonical".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun AiRuntimeConfigCommand.canonical(): String =
        listOf(
            useCase,
            generatorModel,
            evaluatorModel,
            generatorReasoningEffort,
            evaluatorReasoningEffort,
            maxOutputTokens,
            evaluationMaxOutputTokens,
            evaluationEnabled,
            evaluationThreshold,
            maxRevisionCount,
            reason,
        ).joinToString("|")

    companion object {
        private const val TARGET_TYPE = "AI_RUNTIME_CONFIG"
    }
}
