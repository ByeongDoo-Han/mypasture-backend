package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.daily_word.application.DailyWordIncidentAlertPort
import com.example.mypasture.daily_word.application.DailyWordIncidentService
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailOutboxEntity
import com.example.mypasture.daily_word.domain.DailyWordIncidentEmailStatus
import com.example.mypasture.daily_word.domain.DailyWordIncidentEntity
import com.example.mypasture.user.domain.UserRole
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.domain.PageRequest
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** 활성 관리자별로 incident 이메일을 중복 없이 outbox에 적재합니다. */
@Component
class JpaDailyWordIncidentAlertAdapter(
    private val userRepository: UserRepository,
    private val outboxRepository: DailyWordIncidentEmailOutboxRepository,
    private val properties: DailyWordProperties,
    private val clock: Clock = Clock.systemUTC(),
) : DailyWordIncidentAlertPort {
    override fun enqueue(incident: DailyWordIncidentEntity) {
        if (properties.incidentEmailDelivery == "disabled") return
        val now = Instant.now(clock)
        userRepository.findAllByRoleAndDeletedAtIsNull(UserRole.ADMIN).forEach { user ->
            val email = user.email?.trim()?.lowercase()?.takeIf(String::isNotBlank) ?: return@forEach
            if (!outboxRepository.existsByIncident_IdAndNotificationVersionAndRecipientEmail(incident.id, incident.notificationVersion, email)) {
                outboxRepository.save(
                    DailyWordIncidentEmailOutboxEntity(
                        incident = incident,
                        notificationVersion = incident.notificationVersion,
                        recipientUserId = user.id,
                        recipientEmail = email,
                        nextAttemptAt = now,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
            }
        }
    }
}

/** 설정된 주기로 게시 준비 상태를 다시 계산합니다. */
@Component
@ConditionalOnProperty(prefix = "app.daily-word", name = ["incident-detection-enabled"], havingValue = "true")
class DailyWordIncidentMonitor(
    private val service: DailyWordIncidentService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(cron = "\${app.daily-word.incident-detection-cron:0 */10 * * * *}")
    fun detect() {
        // Kotlin 기본 인자 호출이 Spring proxy 필드를 참조하지 않도록 시각을 명시적으로 전달합니다.
        runCatching { service.scan(Instant.now(clock)) }
            .onFailure { logger.error("Daily word incident scan failed", it) }
    }

    companion object { private val logger = LoggerFactory.getLogger(DailyWordIncidentMonitor::class.java) }
}

/** outbox 워커가 실제 메일 채널에 전달할 최소 계약입니다. */
fun interface DailyWordIncidentEmailSender {
    fun send(recipient: String, incident: DailyWordIncidentEntity)
}

/** 운영 환경에서 SMTP로 장애 알림을 발송합니다. */
@Component
@ConditionalOnProperty(prefix = "app.daily-word", name = ["incident-email-delivery"], havingValue = "smtp")
class SmtpDailyWordIncidentEmailSender(
    private val mailSender: JavaMailSender,
    private val properties: DailyWordProperties,
) : DailyWordIncidentEmailSender {
    override fun send(recipient: String, incident: DailyWordIncidentEntity) {
        mailSender.send(SimpleMailMessage().apply {
            from = properties.incidentEmailFrom
            setTo(recipient)
            subject = "[My Pasture] 오늘의 말씀 ${incident.severity} - ${incident.wordDate}"
            text = """
                오늘의 말씀 운영 사건이 감지되었습니다.

                날짜: ${incident.wordDate}
                유형: ${incident.type}
                심각도: ${incident.severity}
                내용: ${incident.message}

                관리자 페이지에서 상태를 확인하고 필요하면 수동 초안을 게시해 주세요.
            """.trimIndent()
        })
    }
}

/** 로컬에서는 수신 주소를 노출하지 않고 메일 발송 결과만 기록합니다. */
@Component
@ConditionalOnProperty(prefix = "app.daily-word", name = ["incident-email-delivery"], havingValue = "log", matchIfMissing = true)
class LoggingDailyWordIncidentEmailSender : DailyWordIncidentEmailSender {
    override fun send(recipient: String, incident: DailyWordIncidentEntity) {
        logger.info(
            "DEV ONLY daily word incident email recipientHash={} incidentId={} severity={} date={}",
            sha256(recipient), incident.id, incident.severity, incident.wordDate,
        )
    }

    companion object { private val logger = LoggerFactory.getLogger(LoggingDailyWordIncidentEmailSender::class.java) }
}

/** 비활성 설정에서도 애플리케이션 구성이 완결되도록 no-op sender를 제공합니다. */
@Component
@ConditionalOnProperty(prefix = "app.daily-word", name = ["incident-email-delivery"], havingValue = "disabled")
class DisabledDailyWordIncidentEmailSender : DailyWordIncidentEmailSender {
    override fun send(recipient: String, incident: DailyWordIncidentEntity) = Unit
}

/** outbox를 짧게 선점한 뒤 외부 SMTP 호출을 트랜잭션 밖에서 실행하고 재시도합니다. */
@Component
@ConditionalOnProperty(prefix = "app.worker", name = ["enabled"], havingValue = "true")
class DailyWordIncidentEmailWorker(
    private val repository: DailyWordIncidentEmailOutboxRepository,
    private val sender: DailyWordIncidentEmailSender,
    private val properties: DailyWordProperties,
    private val transactionTemplate: TransactionTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelayString = "\${app.daily-word.incident-email-poll-delay-ms:30000}")
    fun deliver() {
        if (properties.incidentEmailDelivery == "disabled") return
        claimBatch().forEach { claim ->
            runCatching { sender.send(claim.recipient, claim.incident) }
                .onSuccess { complete(claim) }
                .onFailure { fail(claim, it.message ?: it::class.simpleName ?: "메일 발송 오류") }
        }
    }

    private fun claimBatch(): List<EmailClaim> = transactionTemplate.execute {
        val now = Instant.now(clock)
        repository.findClaimable(
            listOf(DailyWordIncidentEmailStatus.PENDING, DailyWordIncidentEmailStatus.RETRY_WAIT),
            DailyWordIncidentEmailStatus.PROCESSING,
            now,
            PageRequest.of(0, properties.incidentEmailBatchSize),
        ).map { delivery ->
            val token = UUID.randomUUID()
            delivery.claim(token, now.plus(properties.incidentEmailLeaseDuration), now)
            EmailClaim(delivery.id, delivery.recipientEmail, delivery.incident, token, delivery.attemptCount)
        }
    } ?: emptyList()

    private fun complete(claim: EmailClaim) = transactionTemplate.executeWithoutResult {
        repository.findByIdForUpdate(claim.id)?.complete(claim.token, Instant.now(clock))
    }

    private fun fail(claim: EmailClaim, message: String) = transactionTemplate.executeWithoutResult {
        val delivery = repository.findByIdForUpdate(claim.id) ?: return@executeWithoutResult
        val retryAt = if (claim.attempt < properties.incidentEmailMaxAttempts) Instant.now(clock).plus(properties.incidentEmailRetryDelay) else null
        delivery.fail(claim.token, sanitizeDeliveryError(message), retryAt, Instant.now(clock))
        logger.warn("Daily word incident email failed recipientHash={} attempt={}", sha256(claim.recipient), claim.attempt)
    }

    private data class EmailClaim(
        val id: UUID,
        val recipient: String,
        val incident: DailyWordIncidentEntity,
        val token: UUID,
        val attempt: Int,
    )

    companion object { private val logger = LoggerFactory.getLogger(DailyWordIncidentEmailWorker::class.java) }
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }

/** 외부 provider 오류에 포함될 수 있는 이메일과 인증 문자열을 저장 전에 제거합니다. */
private fun sanitizeDeliveryError(value: String): String = value
    .replace(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]")
    .replace(Regex("(?i)(bearer|token|api[-_ ]?key|password)\\s*[:=]?\\s*[^\\s,;]+"), "$1=[redacted]")
    .take(500)
