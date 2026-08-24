package com.example.mypasture.notification.infrastructure

import com.example.mypasture.daily_word.application.DailyWordPublishedPort
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.notification.domain.NotificationBroadcastEntity
import com.example.mypasture.notification.domain.NotificationDestinationType
import com.example.mypasture.notification.domain.NotificationType
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/** 게시된 오늘의 말씀을 90일 동안 노출되는 공통 인앱 알림으로 같은 트랜잭션에 저장합니다. */
@Component
class DailyWordNotificationPublisher(
    private val repository: NotificationBroadcastRepository,
) : DailyWordPublishedPort {
    override fun record(word: DailyWordEntity, publishedAt: Instant) {
        val eventKey = "DAILY_WORD:${word.id}"
        if (repository.existsByEventKey(eventKey)) return
        val databasePublishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS)
        repository.save(
            NotificationBroadcastEntity(
                eventKey = eventKey,
                type = NotificationType.DAILY_WORD,
                title = "오늘의 말씀",
                body = "${word.bookName} ${word.chapter}:${word.verse}",
                destinationType = NotificationDestinationType.TODAY_WORD,
                dailyWord = word,
                publishedAt = databasePublishedAt,
                expiresAt = databasePublishedAt.plus(RETENTION),
            ),
        )
    }

    companion object { private val RETENTION = Duration.ofDays(90) }
}
