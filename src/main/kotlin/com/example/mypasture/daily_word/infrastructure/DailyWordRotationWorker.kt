package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.daily_word.application.DailyWordRotationService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/** worker에서 오늘·내일 본문을 준비하고 실패하면 다음 polling과 기존 사건 감지로 복구합니다. */
@Component
@ConditionalOnProperty(name = ["app.daily-word.rotation-enabled"], havingValue = "true")
class DailyWordRotationWorker(
    private val service: DailyWordRotationService,
    private val properties: DailyWordProperties,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Scheduled(fixedDelayString = "\${app.daily-word.rotation-poll-delay-ms:30000}")
    fun prepare() {
        if (!properties.generationEnabled) return
        val today = LocalDate.now(clock.withZone(DailyWordRotationService.SEOUL))
        listOf(today, today.plusDays(1)).forEach { date ->
            runCatching { service.ensurePublished(date, today) }
                .onFailure { logger.warn("Daily word rotation preparation failed for date={}: {}", date, it.javaClass.simpleName) }
        }
    }

    companion object { private val logger = LoggerFactory.getLogger(DailyWordRotationWorker::class.java) }
}
