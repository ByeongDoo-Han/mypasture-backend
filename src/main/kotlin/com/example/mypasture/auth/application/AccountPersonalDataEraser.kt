package com.example.mypasture.auth.application

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/** 회원탈퇴 트랜잭션에서 개인 기록을 삭제하고 공동체 기록의 계정 연결을 익명화합니다. */
@Component
class AccountPersonalDataEraser(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val existingTables: Set<String> by lazy {
        val dataSource = requireNotNull(jdbcTemplate.dataSource) { "Account erasure requires a DataSource." }
        dataSource.connection.use { connection ->
            connection.metaData.getTables(null, null, null, arrayOf("TABLE")).use { tables ->
                buildSet {
                    while (tables.next()) add(tables.getString("TABLE_NAME").lowercase())
                }
            }
        }
    }

    fun erase(userId: UUID, email: String?) {
        deleteAiData(userId)
        deleteNotificationData(userId)
        deleteFaithProfileData(userId)
        anonymizeCommunityData(userId)
        PERSONAL_DATA_TABLES.forEach { table ->
            update(setOf(table), "delete from $table where user_id = ?", userId)
        }
        email?.let {
            update(
                setOf("email_signup_verifications"),
                "delete from email_signup_verifications where lower(email) = lower(?)",
                it,
            )
        }
    }

    private fun deleteAiData(userId: UUID) {
        update(
            setOf("ai_messages_legacy", "ai_conversations_legacy"),
            "delete from ai_messages_legacy where conversation_id in (select id from ai_conversations_legacy where user_id = ?)",
            userId,
        )
        update(setOf("ai_conversations_legacy"), "delete from ai_conversations_legacy where user_id = ?", userId)
        update(setOf("ai_conversations"), "delete from ai_conversations where user_id = ?", userId)
        // ai_request_receipts와 ai_answer_feedbacks는 usage log의 ON DELETE CASCADE로 함께 제거됩니다.
        update(setOf("ai_usage_logs"), "delete from ai_usage_logs where user_id = ?", userId)
    }

    private fun deleteNotificationData(userId: UUID) {
        update(setOf("notification_device_deliveries"), "delete from notification_device_deliveries where user_id = ?", userId)
        update(setOf("notification_devices"), "delete from notification_devices where user_id = ?", userId)
        update(setOf("daily_word_incident_email_outbox"), "delete from daily_word_incident_email_outbox where recipient_user_id = ?", userId)
    }

    private fun deleteFaithProfileData(userId: UUID) {
        update(
            setOf("faith_dimension_scores", "faith_results", "faith_assessments"),
            "delete from faith_dimension_scores where result_id in " +
                "(select result.id from faith_results result join faith_assessments assessment " +
                "on assessment.id = result.assessment_id where assessment.user_id = ?)",
            userId,
        )
        update(
            setOf("faith_results", "faith_assessments"),
            "delete from faith_results where assessment_id in (select id from faith_assessments where user_id = ?)",
            userId,
        )
        update(
            setOf("faith_answers", "faith_assessments"),
            "delete from faith_answers where assessment_id in (select id from faith_assessments where user_id = ?)",
            userId,
        )
        update(setOf("faith_assessments"), "delete from faith_assessments where user_id = ?", userId)
    }

    private fun anonymizeCommunityData(userId: UUID) {
        update(
            setOf("quiet_time_session_participants"),
            "update quiet_time_session_participants set user_id = null, display_name = '탈퇴한 사용자', " +
                "device_id = 'deleted-' || cast(id as varchar) where user_id = ?",
            userId,
        )
        update(
            setOf("quiet_time_question_answers"),
            "update quiet_time_question_answers set user_id = null, author_display_name = '탈퇴한 사용자' where user_id = ?",
            userId,
        )
        update(setOf("moderation_reports"), "delete from moderation_reports where reporter_user_id = ?", userId)
        update(setOf("moderation_reports"), "update moderation_reports set target_user_id = null where target_user_id = ?", userId)
        update(setOf("moderation_reports"), "update moderation_reports set resolved_by = null where resolved_by = ?", userId)
        update(setOf("user_blocks"), "delete from user_blocks where blocker_user_id = ? or blocked_user_id = ?", userId, userId)
    }

    private fun update(requiredTables: Set<String>, sql: String, vararg args: Any) {
        if (requiredTables.all(existingTables::contains)) jdbcTemplate.update(sql, *args)
    }

    companion object {
        private val PERSONAL_DATA_TABLES = listOf(
            "bible_notes",
            "highlights",
            "bookmarks",
            "reading_progress",
            "bible_reading_states",
            "bible_reading_history",
            "prayers",
            "journal_entries",
            "pasture_members",
            "challenge_participants",
            "quiet_time_reflections",
            "pasture_attendances",
            "daily_word_notification_settings",
            "notification_deliveries",
            "notification_read_receipts",
            "notification_read_watermarks",
            "user_collectibles",
            "coupon_redemptions",
            "user_entitlements",
            "user_policy_consents",
            "password_reset_tokens",
            "auth_refresh_tokens",
        )
    }
}
