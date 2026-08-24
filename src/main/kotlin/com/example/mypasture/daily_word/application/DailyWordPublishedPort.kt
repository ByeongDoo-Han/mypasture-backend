package com.example.mypasture.daily_word.application

import com.example.mypasture.daily_word.domain.DailyWordEntity
import java.time.Instant

/** 게시 트랜잭션 안에서 후속 사용자 알림을 기록하는 도메인 경계입니다. */
fun interface DailyWordPublishedPort {
    fun record(word: DailyWordEntity, publishedAt: Instant)
}
