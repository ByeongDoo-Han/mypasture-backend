package com.example.mypasture.daily_word.infrastructure

import com.example.mypasture.daily_word.domain.DailyWordEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.UUID

/** 날짜별 오늘의 말씀을 한 번만 저장하는 repository입니다. */
interface DailyWordRepository : JpaRepository<DailyWordEntity, UUID> {
    fun findByWordDateAndPublishedTrue(wordDate: LocalDate): DailyWordEntity?
}
