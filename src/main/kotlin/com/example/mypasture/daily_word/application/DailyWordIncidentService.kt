package com.example.mypasture.daily_word.application

import com.example.mypasture.admin_audit.domain.AdminAuditAction
import com.example.mypasture.admin_audit.domain.AdminAuditLogEntity
import com.example.mypasture.admin_audit.infrastructure.AdminAuditLogRepository
import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobEntity
import com.example.mypasture.daily_word.domain.DailyWordGenerationJobStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentSeverity
import com.example.mypasture.daily_word.domain.DailyWordIncidentStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentType
import com.example.mypasture.daily_word.domain.DailyWordStatus
import com.example.mypasture.daily_word.infrastructure.DailyWordGenerationJobRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordIncidentRepository
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** 새 운영 사건 알림을 영속 outbox에 예약하는 application port입니다. */
fun interface DailyWordIncidentAlertPort {
    fun enqueue(incident: DailyWordIncidentEntity)
}

/** 게시 누락과 생성 장애를 감지하고 관리자 확인·해결 이력을 관리합니다. */
@Service
@Transactional(readOnly = true)
class DailyWordIncidentService(
    private val incidentRepository: DailyWordIncidentRepository,
    private val wordRepository: DailyWordRepository,
    private val jobRepository: DailyWordGenerationJobRepository,
    private val auditRepository: AdminAuditLogRepository,
    private val alertPort: DailyWordIncidentAlertPort,
    private val properties: DailyWordProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(status: DailyWordIncidentStatus?): List<DailyWordIncidentEntity> =
        status?.let(incidentRepository::findTop100ByStatusOrderByLastDetectedAtDesc)
            ?: incidentRepository.findTop100ByOrderByLastDetectedAtDesc()

    /** 현재 운영 상태를 날짜·유형별 incident로 반영하고 해소된 조건은 자동 종료합니다. */
    @Transactional
    fun scan(now: Instant) {
        val zone = ZoneId.of(properties.generationZone)
        val today = LocalDate.ofInstant(now, zone)
        detect(today, DailyWordIncidentSeverity.CRITICAL, now)

        val localTime = now.atZone(zone).toLocalTime()
        if (!localTime.isBefore(properties.incidentWarningTime)) {
            val severity = if (localTime.isBefore(properties.incidentCriticalTime)) {
                DailyWordIncidentSeverity.WARNING
            } else {
                DailyWordIncidentSeverity.CRITICAL
            }
            detect(today.plusDays(1), severity, now)
        } else {
            resolveIfPresent(DailyWordIncidentType.PUBLISHING_MISSING, today.plusDays(1), "게시 준비 경고 시간 전입니다.", now)
            resolveIfPresent(DailyWordIncidentType.GENERATION_FAILED, today.plusDays(1), "게시 준비 경고 시간 전입니다.", now)
            resolveIfPresent(DailyWordIncidentType.GENERATION_STALLED, today.plusDays(1), "게시 준비 경고 시간 전입니다.", now)
        }
    }

    @Transactional
    fun acknowledge(id: UUID, adminId: UUID, operationId: UUID, reason: String): DailyWordIncidentEntity =
        changeState(id, adminId, operationId, reason, AdminAuditAction.DAILY_WORD_INCIDENT_ACKNOWLEDGED) { incident, normalized, now ->
            incident.acknowledge(adminId, now)
            "status=${incident.status};reason=$normalized"
        }

    @Transactional
    fun resolve(id: UUID, adminId: UUID, operationId: UUID, reason: String): DailyWordIncidentEntity =
        changeState(id, adminId, operationId, reason, AdminAuditAction.DAILY_WORD_INCIDENT_RESOLVED) { incident, normalized, now ->
            incident.resolve(adminId, normalized, now)
            "status=${incident.status};reason=$normalized"
        }

    private fun detect(date: LocalDate, publishingSeverity: DailyWordIncidentSeverity, now: Instant) {
        val word = wordRepository.findByWordDate(date)
        val published = word?.status == DailyWordStatus.PUBLISHED
        observeOrResolve(
            DailyWordIncidentType.PUBLISHING_MISSING,
            date,
            !published,
            publishingSeverity,
            if (word == null) "$date 오늘의 말씀 콘텐츠가 없습니다." else "$date 오늘의 말씀이 게시되지 않았습니다. 현재 상태=${word.status}",
            null,
            now,
        )

        val usableDraft = word?.status == DailyWordStatus.DRAFT || published
        val failed = if (usableDraft) null else jobRepository.findFirstByWordDateAndStatusOrderByUpdatedAtDesc(date, DailyWordGenerationJobStatus.FAILED)
        observeOrResolve(
            DailyWordIncidentType.GENERATION_FAILED,
            date,
            failed != null,
            publishingSeverity,
            failed?.errorMessage?.let { "$date 생성 작업이 최종 실패했습니다: ${it.take(700)}" } ?: "$date 생성 실패 조건이 해소되었습니다.",
            failed,
            now,
        )

        val stale = if (usableDraft) null else jobRepository.findFirstByWordDateAndStatusInAndUpdatedAtBeforeOrderByUpdatedAtAsc(
            date,
            ACTIVE_JOB_STATUSES,
            now.minus(properties.incidentStalledThreshold),
        )
        observeOrResolve(
            DailyWordIncidentType.GENERATION_STALLED,
            date,
            stale != null,
            publishingSeverity,
            stale?.let { "$date 생성 작업이 ${properties.incidentStalledThreshold.toMinutes()}분 이상 진행되지 않았습니다." }
                ?: "$date 생성 지연 조건이 해소되었습니다.",
            stale,
            now,
        )
    }

    private fun observeOrResolve(
        type: DailyWordIncidentType,
        date: LocalDate,
        active: Boolean,
        severity: DailyWordIncidentSeverity,
        message: String,
        job: DailyWordGenerationJobEntity?,
        now: Instant,
    ) {
        val current = incidentRepository.findByTypeAndWordDateForUpdate(type, date)
        if (!active) {
            if (current != null && current.status != DailyWordIncidentStatus.RESOLVED) {
                current.resolve(null, "자동 감지 조건이 해소되었습니다.", now)
            }
            return
        }
        if (current == null) {
            val created = incidentRepository.saveAndFlush(
                DailyWordIncidentEntity(
                    wordDate = date,
                    type = type,
                    severity = severity,
                    message = message.take(1_000),
                    latestJobId = job?.id,
                    firstDetectedAt = now,
                    lastDetectedAt = now,
                ),
            )
            alertPort.enqueue(created)
        } else if (current.observe(severity, message, job?.id, now)) {
            alertPort.enqueue(current)
        }
    }

    private fun resolveIfPresent(type: DailyWordIncidentType, date: LocalDate, note: String, now: Instant) {
        incidentRepository.findByTypeAndWordDateForUpdate(type, date)
            ?.takeIf { it.status != DailyWordIncidentStatus.RESOLVED }
            ?.resolve(null, note, now)
    }

    private fun changeState(
        id: UUID,
        adminId: UUID,
        operationId: UUID,
        reason: String,
        action: AdminAuditAction,
        block: (DailyWordIncidentEntity, String, Instant) -> String,
    ): DailyWordIncidentEntity {
        val normalized = reason.trim().also { require(it.length in 5..500) { "관리자 조치 사유는 5자에서 500자 사이여야 합니다." } }
        val requestFingerprint = fingerprint(action.name, "$id|$normalized")
        auditRepository.findByOperationId(operationId)?.let {
            if (it.action != action || it.targetType != TARGET_TYPE || it.targetId != id || it.requestFingerprint != requestFingerprint) {
                throw DailyWordOperationConflictException()
            }
            return incidentRepository.findById(id).orElseThrow { DailyWordNotFoundException("운영 사건을 찾을 수 없습니다.") }
        }
        val incident = incidentRepository.findByIdForUpdate(id) ?: throw DailyWordNotFoundException("운영 사건을 찾을 수 없습니다.")
        val details = block(incident, normalized, Instant.now(clock))
        auditRepository.save(
            AdminAuditLogEntity(
                operationId = operationId,
                adminUserId = adminId,
                action = action,
                targetType = TARGET_TYPE,
                targetId = incident.id,
                reason = normalized,
                details = details,
                requestFingerprint = requestFingerprint,
                createdAt = Instant.now(clock),
            ),
        )
        return incident
    }

    private fun fingerprint(action: String, canonical: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$action|$canonical".toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TARGET_TYPE = "DAILY_WORD_INCIDENT"
        private val ACTIVE_JOB_STATUSES = listOf(DailyWordGenerationJobStatus.PENDING, DailyWordGenerationJobStatus.PROCESSING)
    }
}
