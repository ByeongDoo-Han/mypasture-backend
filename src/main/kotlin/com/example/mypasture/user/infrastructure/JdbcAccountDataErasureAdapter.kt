package com.example.mypasture.user.infrastructure

import com.example.mypasture.user.application.AccountDataErasure
import com.example.mypasture.user.application.AccountDataErasureCommand
import com.example.mypasture.user.application.AccountDataErasureResult
import com.example.mypasture.user.domain.AccountErasureAuditEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceUtils
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource

/** 계정 데이터의 외래키 순서를 통제해 삭제와 공동 기록 익명화를 한 트랜잭션에서 수행합니다. */
@Component
class JdbcAccountDataErasureAdapter(
    private val dataSource: DataSource,
    private val jdbcTemplate: JdbcTemplate,
    private val auditRepository: AccountErasureAuditRepository,
) : AccountDataErasure {
    override fun erase(command: AccountDataErasureCommand): AccountDataErasureResult {
        val tables = existingTables()
        // AuthService가 이 포트를 호출하기 직전에 익명화한 사용자 tombstone 한 행을 포함합니다.
        var anonymized = 1L
        var deleted = 0L

        fun update(table: String, sql: String, vararg parameters: Any): Int {
            if (table !in tables) return 0
            return jdbcTemplate.update(sql, *parameters)
        }

        // 공동체와 운영 콘텐츠는 유지하되, 노출 가능한 작성자 정보와 nullable 사용자 연결은 제거합니다.
        anonymized += update(
            "quiet_time_session_participants",
            """
            update quiet_time_session_participants
               set user_id = null,
                   display_name = '탈퇴한 참여자',
                   device_id = concat('withdrawn-', cast(id as varchar(120)))
             where user_id = ?
            """.trimIndent(),
            command.userId,
        )
        anonymized += update(
            "quiet_time_pastures",
            "update quiet_time_pastures set leader_user_id = null, leader_name = '탈퇴한 사용자' where leader_user_id = ?",
            command.userId,
        )
        anonymized += update(
            "quiet_time_sessions",
            """
            update quiet_time_sessions
               set host_user_id = case when scope = 'PASTURE' then null else host_user_id end,
                   host_display_name = '탈퇴한 사용자'
             where host_user_id = ?
            """.trimIndent(),
            command.userId,
        )
        anonymized += update(
            "quiet_time_shared_question_sets",
            "update quiet_time_shared_question_sets set created_by_user_id = null where created_by_user_id = ?",
            command.userId,
        )
        anonymized += update("ai_runtime_config_versions", "update ai_runtime_config_versions set created_by = null where created_by = ?", command.userId)
        anonymized += update("daily_words", "update daily_words set reviewed_by = null where reviewed_by = ?", command.userId)
        anonymized += update("daily_word_generation_jobs", "update daily_word_generation_jobs set requested_by = null where requested_by = ?", command.userId)
        anonymized += update(
            "daily_word_incidents",
            """
            update daily_word_incidents
               set acknowledged_by = case when acknowledged_by = ? then null else acknowledged_by end,
                   resolved_by = case when resolved_by = ? then null else resolved_by end
             where acknowledged_by = ? or resolved_by = ?
            """.trimIndent(),
            command.userId,
            command.userId,
            command.userId,
            command.userId,
        )
        anonymized += update(
            "bible_commentary_generation_jobs",
            "update bible_commentary_generation_jobs set requested_by = null where requested_by = ?",
            command.userId,
        )
        anonymized += update(
            "bible_chapter_commentaries",
            """
            update bible_chapter_commentaries
               set reviewed_by = case when reviewed_by = ? then null else reviewed_by end,
                   last_edited_by = case when last_edited_by = ? then null else last_edited_by end
             where reviewed_by = ? or last_edited_by = ?
            """.trimIndent(),
            command.userId,
            command.userId,
            command.userId,
            command.userId,
        )
        anonymized += update(
            "bible_commentary_evidence_notes",
            "update bible_commentary_evidence_notes set approved_by = null where approved_by = ?",
            command.userId,
        )

        // 사용자 소유 데이터는 자식 테이블부터 물리 삭제해 원문과 토큰을 남기지 않습니다.
        deleted += update(
            "quiet_time_question_generation_attempts",
            "delete from quiet_time_question_generation_attempts where user_id = ?",
            command.userId,
        )
        deleted += update("user_achievement_featured", "delete from user_achievement_featured where user_id = ?", command.userId)
        deleted += update("user_achievement_awards", "delete from user_achievement_awards where user_id = ?", command.userId)
        deleted += update("user_note_activity_days", "delete from user_note_activity_days where user_id = ?", command.userId)
        deleted += update(
            "ai_answer_feedback_reasons",
            "delete from ai_answer_feedback_reasons where feedback_id in (select id from ai_answer_feedbacks where user_id = ?)",
            command.userId,
        )
        deleted += update("ai_request_receipts", "delete from ai_request_receipts where user_id = ?", command.userId)
        deleted += update("ai_answer_feedbacks", "delete from ai_answer_feedbacks where user_id = ?", command.userId)
        deleted += update(
            "ai_messages_legacy",
            "delete from ai_messages_legacy where conversation_id in (select id from ai_conversations_legacy where user_id = ?)",
            command.userId,
        )
        deleted += update("ai_conversations_legacy", "delete from ai_conversations_legacy where user_id = ?", command.userId)
        deleted += update("ai_conversations", "delete from ai_conversations where user_id = ?", command.userId)
        deleted += update("ai_answer_history", "delete from ai_answer_history where user_id = ?", command.userId)
        deleted += update("ai_usage_logs", "delete from ai_usage_logs where user_id = ?", command.userId)

        deleted += update(
            "bible_note_verses",
            "delete from bible_note_verses where note_id in (select id from bible_notes where user_id = ?)",
            command.userId,
        )
        deleted += update("scripture_copies", "delete from scripture_copies where user_id = ?", command.userId)
        deleted += update("bible_notes", "delete from bible_notes where user_id = ?", command.userId)
        deleted += update("bookmarks", "delete from bookmarks where user_id = ?", command.userId)
        deleted += update("highlights", "delete from highlights where user_id = ?", command.userId)
        deleted += update("reading_progress", "delete from reading_progress where user_id = ?", command.userId)
        deleted += update("bible_reading_states", "delete from bible_reading_states where user_id = ?", command.userId)
        deleted += update("user_visits", "delete from user_visits where user_id = ?", command.userId)
        deleted += update("bible_reading_history", "delete from bible_reading_history where user_id = ?", command.userId)

        deleted += update("prayers", "delete from prayers where user_id = ?", command.userId)
        deleted += update("journal_entries", "delete from journal_entries where user_id = ?", command.userId)
        deleted += update("quiet_time_question_answers", "delete from quiet_time_question_answers where user_id = ?", command.userId)
        deleted += update("quiet_time_reflections", "delete from quiet_time_reflections where user_id = ?", command.userId)
        deleted += update("pasture_attendances", "delete from pasture_attendances where user_id = ?", command.userId)
        deleted += update("pasture_members", "delete from pasture_members where user_id = ?", command.userId)
        deleted += update("challenge_participants", "delete from challenge_participants where user_id = ?", command.userId)

        deleted += update(
            "faith_dimension_scores",
            """
            delete from faith_dimension_scores
             where result_id in (
               select result.id from faith_results result
               join faith_assessments assessment on assessment.id = result.assessment_id
               where assessment.user_id = ?
             )
            """.trimIndent(),
            command.userId,
        )
        deleted += update(
            "faith_results",
            "delete from faith_results where assessment_id in (select id from faith_assessments where user_id = ?)",
            command.userId,
        )
        deleted += update(
            "faith_answers",
            "delete from faith_answers where assessment_id in (select id from faith_assessments where user_id = ?)",
            command.userId,
        )
        deleted += update("faith_assessments", "delete from faith_assessments where user_id = ?", command.userId)

        deleted += update("notification_device_deliveries", "delete from notification_device_deliveries where user_id = ?", command.userId)
        deleted += update("notification_deliveries", "delete from notification_deliveries where user_id = ?", command.userId)
        deleted += update("daily_word_incident_email_outbox", "delete from daily_word_incident_email_outbox where recipient_user_id = ?", command.userId)
        deleted += update("notification_read_receipts", "delete from notification_read_receipts where user_id = ?", command.userId)
        deleted += update("notification_read_watermarks", "delete from notification_read_watermarks where user_id = ?", command.userId)
        deleted += update("notification_devices", "delete from notification_devices where user_id = ?", command.userId)
        deleted += update("daily_word_notification_settings", "delete from daily_word_notification_settings where user_id = ?", command.userId)

        deleted += update("moderation_reports", "delete from moderation_reports where reporter_user_id = ?", command.userId)
        anonymized += update("moderation_reports", "update moderation_reports set target_user_id = null where target_user_id = ?", command.userId)
        anonymized += update("moderation_reports", "update moderation_reports set resolved_by = null where resolved_by = ?", command.userId)
        deleted += update(
            "user_blocks",
            "delete from user_blocks where blocker_user_id = ? or blocked_user_id = ?",
            command.userId,
            command.userId,
        )

        deleted += update("user_avatar_platforms", "delete from user_avatar_platforms where user_id = ?", command.userId)
        deleted += update("user_collectibles", "delete from user_collectibles where user_id = ?", command.userId)
        deleted += update("user_entitlements", "delete from user_entitlements where user_id = ?", command.userId)
        deleted += update("coupon_redemptions", "delete from coupon_redemptions where user_id = ?", command.userId)
        deleted += update("user_policy_consent_events", "delete from user_policy_consent_events where user_id = ?", command.userId)
        deleted += update("user_policy_consents", "delete from user_policy_consents where user_id = ?", command.userId)

        deleted += update("password_reset_tokens", "delete from password_reset_tokens where user_id = ?", command.userId)
        deleted += update("auth_refresh_tokens", "delete from auth_refresh_tokens where user_id = ?", command.userId)
        deleted += update("auth_accounts", "delete from auth_accounts where user_id = ?", command.userId)
        command.email?.let { email ->
            deleted += update("email_signup_verifications", "delete from email_signup_verifications where lower(email) = lower(?)", email)
        }

        auditRepository.save(
            AccountErasureAuditEntity(
                id = command.requestId,
                requestedAt = command.requestedAt,
                completedAt = Instant.now(),
                policyVersion = ACCOUNT_ERASURE_POLICY_VERSION,
                deletedRecordCount = deleted,
                anonymizedRecordCount = anonymized,
            ),
        )
        return AccountDataErasureResult(deleted, anonymized)
    }

    private fun existingTables(): Set<String> {
        val connection = DataSourceUtils.getConnection(dataSource)
        return try {
            connection.metaData.getTables(connection.catalog, null, "%", arrayOf("TABLE")).use { rows ->
                buildSet {
                    while (rows.next()) add(rows.getString("TABLE_NAME").lowercase(Locale.ROOT))
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource)
        }
    }

    companion object {
        const val ACCOUNT_ERASURE_POLICY_VERSION = "account-erasure-v1"
    }
}
