package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.config.DailyWordProperties
import com.example.mypasture.daily_word.domain.DailyWordRotationLockEntity
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component

/** Flyway 없이 Hibernate schema를 만드는 로컬·테스트에서도 배정 잠금 행을 준비합니다. */
@Component
@Profile("local", "test")
class DailyWordRotationLocalInitializer(
    private val guard: DailyWordRotationLockRepository,
    private val properties: DailyWordProperties,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (properties.rotationEnabled && !guard.existsById(1)) guard.saveAndFlush(DailyWordRotationLockEntity())
    }
}
