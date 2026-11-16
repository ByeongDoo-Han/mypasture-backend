package com.example.mypasture.quiet_time.infrastructure

import com.example.mypasture.quiet_time.application.GeneratedQuietTimeQuestions
import com.example.mypasture.quiet_time.application.QuietTimeQuestionSet
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.sql.Timestamp

/** PostgreSQL 고유 제약과 소유 토큰으로 공용 질문 생성권을 한 요청에만 부여합니다. */
@Repository
class QuietTimeSharedQuestionStore(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    fun claim(passageId: UUID, style: QuietTimeQuestionStyle, version: Int, sourceHash: String, userId: UUID, now: Instant): UUID? =
        transactionTemplate.execute {
        // 사용자 행을 잠그면 서로 다른 본문을 동시에 생성해도 하루 한도를 초과하지 않습니다.
        jdbc.queryForObject("select id from users where id = ? for update", UUID::class.java, userId)
        val token = UUID.randomUUID()
        val inserted = jdbc.update(
            """
            insert into quiet_time_shared_question_sets(
              id, passage_id, focus_theme_code, question_style, generator_version, source_hash, created_by_user_id,
              status, claim_token, lease_expires_at, created_at
            ) values (?, ?, 'GENERAL', ?, ?, ?, ?, 'GENERATING', ?, ?, ?)
            on conflict do nothing
            """.trimIndent(),
            UUID.randomUUID(), passageId, style.name, version, sourceHash, userId, token, Timestamp.from(now.plusSeconds(180)), Timestamp.from(now),
        )
        if (inserted == 1) {
            requireWithinDailyLimit(userId, now)
            return@execute token
        }
        val takenOver = jdbc.update(
            """
            update quiet_time_shared_question_sets
               set status = 'GENERATING', claim_token = ?, lease_expires_at = ?, question_set_json = null,
                   created_by_user_id = ?, created_at = ?
             where passage_id = ? and focus_theme_code = 'GENERAL' and question_style = ?
               and generator_version = ? and source_hash = ?
               and (status = 'FAILED' or (status = 'GENERATING' and lease_expires_at < ?))
            """.trimIndent(),
            token, Timestamp.from(now.plusSeconds(180)), userId, Timestamp.from(now), passageId, style.name, version, sourceHash, Timestamp.from(now),
        )
        if (takenOver == 1) requireWithinDailyLimit(userId, now)
        token.takeIf { takenOver == 1 }
    }

    private fun requireWithinDailyLimit(userId: UUID, now: Instant) {
        jdbc.update(
            "insert into quiet_time_question_generation_attempts(id, user_id, created_at) values (?, ?, ?)",
            UUID.randomUUID(), userId, Timestamp.from(now),
        )
        val zone = ZoneId.of("Asia/Seoul")
        val start = LocalDate.ofInstant(now, zone).atStartOfDay(zone).toInstant()
        val end = LocalDate.ofInstant(now, zone).plusDays(1).atStartOfDay(zone).toInstant()
        val count = jdbc.queryForObject(
            "select count(*) from quiet_time_question_generation_attempts where user_id = ? and created_at >= ? and created_at < ?",
            Int::class.java, userId, Timestamp.from(start), Timestamp.from(end),
        ) ?: 0
        if (count > MAX_DAILY_ATTEMPTS) throw QuietTimeGenerationAttemptLimitException()
        val activeCount = jdbc.queryForObject(
            """
            select count(*) from quiet_time_shared_question_sets
             where created_by_user_id = ? and created_at >= ? and created_at < ?
               and (status = 'READY' or (status = 'GENERATING' and lease_expires_at >= ?))
            """.trimIndent(),
            Int::class.java, userId, Timestamp.from(start), Timestamp.from(end), Timestamp.from(now),
        ) ?: 0
        if (activeCount > MAX_DAILY_QUESTIONS) throw QuietTimeGenerationLimitException()
    }

    fun state(passageId: UUID, style: QuietTimeQuestionStyle, version: Int, sourceHash: String): StoredQuietTimeQuestions? =
        jdbc.query(
            """
            select status, question_set_json, lease_expires_at from quiet_time_shared_question_sets
             where passage_id = ? and focus_theme_code = 'GENERAL' and question_style = ?
               and generator_version = ? and source_hash = ?
            """.trimIndent(),
            { rs, _ -> StoredQuietTimeQuestions(
                if (rs.getString("status") == "GENERATING" && rs.getTimestamp("lease_expires_at").toInstant().isBefore(Instant.now())) "FAILED" else rs.getString("status"),
                rs.getString("question_set_json")?.let { objectMapper.readValue(it, QuietTimeQuestionSet::class.java) },
            ) },
            passageId, style.name, version, sourceHash,
        ).firstOrNull()

    fun complete(
        passageId: UUID, style: QuietTimeQuestionStyle, version: Int, sourceHash: String,
        token: UUID, result: QuietTimeQuestionSet, usage: GeneratedQuietTimeQuestions,
    ): Boolean = jdbc.update(
        """
        update quiet_time_shared_question_sets
           set status = 'READY', question_set_json = ?, model = ?, prompt_tokens = ?,
               completion_tokens = ?, estimated_cost_usd = ?, completed_at = ?
         where passage_id = ? and focus_theme_code = 'GENERAL' and question_style = ?
           and generator_version = ? and source_hash = ? and claim_token = ? and status = 'GENERATING'
        """.trimIndent(),
        objectMapper.writeValueAsString(result), usage.model, usage.promptTokens, usage.completionTokens,
        usage.estimatedCostUsd, Timestamp.from(Instant.now()), passageId, style.name, version, sourceHash, token,
    ) == 1

    fun fail(passageId: UUID, style: QuietTimeQuestionStyle, version: Int, sourceHash: String, token: UUID) {
        jdbc.update(
            """
            update quiet_time_shared_question_sets set status = 'FAILED'
             where passage_id = ? and focus_theme_code = 'GENERAL' and question_style = ?
               and generator_version = ? and source_hash = ? and claim_token = ? and status = 'GENERATING'
            """.trimIndent(),
            passageId, style.name, version, sourceHash, token,
        )
    }

    companion object {
        private const val MAX_DAILY_QUESTIONS = 3
        private const val MAX_DAILY_ATTEMPTS = 10
    }
}

/** 공용 질문 작업의 현재 상태와 완성된 질문 묶음입니다. */
data class StoredQuietTimeQuestions(val status: String, val questionSet: QuietTimeQuestionSet?)

/** 광고·결제 연동 전 AI 비용을 제한하기 위한 목자별 일일 생성 한도입니다. */
class QuietTimeGenerationLimitException : RuntimeException("오늘 생성할 수 있는 새 QT 질문은 3개까지입니다.")

/** 실패한 AI 호출까지 포함한 목자별 일일 비용 상한입니다. */
class QuietTimeGenerationAttemptLimitException : RuntimeException("오늘 QT 질문 생성 시도는 10회까지입니다. 내일 다시 시도해 주세요.")
