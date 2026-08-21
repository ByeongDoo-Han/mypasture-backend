package com.example.mypasture.quiet_time.application

/** 커밋된 QT 답변 변경을 로컬 또는 Redis 실시간 채널로 전달하는 출력 포트입니다. */
interface QuietTimeAnswerRealtimePort {
    suspend fun publish(event: QuietTimeAnswerChangedEvent)
}
