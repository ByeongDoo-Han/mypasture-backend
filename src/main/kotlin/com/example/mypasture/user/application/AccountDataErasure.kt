package com.example.mypasture.user.application

import java.time.Instant
import java.util.UUID

/** 탈퇴한 사용자의 개인 데이터를 삭제하고 공동 기록을 익명화하기 위한 입력입니다. */
data class AccountDataErasureCommand(
    val requestId: UUID = UUID.randomUUID(),
    val userId: UUID,
    val email: String?,
    val requestedAt: Instant,
)

/** 회원탈퇴 트랜잭션에서 처리한 삭제 및 익명화 행 수를 반환합니다. */
data class AccountDataErasureResult(
    val deletedRecordCount: Long,
    val anonymizedRecordCount: Long,
)

/** 인증 도메인이 다른 도메인의 저장 기술을 알지 않고 계정 데이터 파기를 요청하는 포트입니다. */
fun interface AccountDataErasure {
    fun erase(command: AccountDataErasureCommand): AccountDataErasureResult
}

/** DB 커밋 뒤 사용자별 외부 캐시를 제거하기 위해 발행하는 도메인 이벤트입니다. */
data class AccountDeletedEvent(val userId: UUID)
