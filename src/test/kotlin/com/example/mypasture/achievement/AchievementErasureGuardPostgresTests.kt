package com.example.mypasture.achievement

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/** 실제 PostgreSQL에서 구버전 계정 탈퇴의 업적 삭제와 트랜잭션 rollback을 검증합니다. */
@EnabledIfEnvironmentVariable(named = "ACHIEVEMENT_TEST_JDBC_URL", matches = ".+")
class AchievementErasureGuardPostgresTests {
    private val jdbcUrl get() = System.getenv("ACHIEVEMENT_TEST_JDBC_URL")
    private val jdbcUser get() = System.getenv("ACHIEVEMENT_TEST_JDBC_USER") ?: "postgres"
    private val jdbcPassword get() = System.getenv("ACHIEVEMENT_TEST_JDBC_PASSWORD") ?: ""

    private fun connection(schema: String): Connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).also { connection ->
        assertEquals("PostgreSQL", connection.metaData.databaseProductName)
        connection.sql("SET search_path TO $schema, public")
    }

    private fun database(test: (String) -> Unit) {
        val schema = "achievement_erasure_${UUID.randomUUID().toString().replace("-", "") }"
        val flyway = Flyway.configure().dataSource(jdbcUrl, jdbcUser, jdbcPassword)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(false).load()
        try {
            flyway.migrate()
            test(schema)
        } finally { flyway.clean() }
    }

    private fun Connection.sql(value: String): Int = createStatement().use { it.executeUpdate(value) }
    private fun Connection.count(value: String): Int = createStatement().use { statement ->
        statement.executeQuery(value).use { result -> result.next(); result.getInt(1) }
    }

    /** 업적을 모르는 구버전 API도 탈퇴 시 users.deleted_at을 먼저 갱신합니다. */
    private fun Connection.owner(): UUID = UUID.randomUUID().also { userId ->
        sql("INSERT INTO users(id, display_name) VALUES ('$userId', 'guard-fixture')")
        sql("INSERT INTO user_achievement_awards(id, user_id, achievement_code, awarded_at) VALUES ('${UUID.randomUUID()}', '$userId', 'FIRST_CHAPTER', now())")
        sql("INSERT INTO user_note_activity_days(id, user_id, recorded_on) VALUES ('${UUID.randomUUID()}', '$userId', DATE '2026-10-08')")
        sql("INSERT INTO user_achievement_featured(id, user_id, achievement_code, position) VALUES ('${UUID.randomUUID()}', '$userId', 'FIRST_CHAPTER', 0)")
    }

    private fun Connection.assertEvidence(userId: UUID, expected: Int) {
        for (table in listOf("user_achievement_featured", "user_achievement_awards", "user_note_activity_days")) {
            assertEquals(expected, count("SELECT count(*) FROM $table WHERE user_id='$userId'"), "$table must retain only this owner's expected evidence")
        }
    }

    @Test fun `기존 소프트 탈퇴는 모든 업적 근거를 삭제하고 다른 사용자 기록은 보존한다`() = database { schema ->
        connection(schema).use { connection ->
            val owner = connection.owner()
            val other = connection.owner()
            // No achievement-aware application erasure adapter is called here.
            assertEquals(1, connection.sql("UPDATE users SET deleted_at=now(), display_name='withdrawn' WHERE id='$owner' AND deleted_at IS NULL"))
            connection.assertEvidence(owner, 0)
            connection.assertEvidence(other, 1)
            assertEquals(1, connection.count("SELECT count(*) FROM users WHERE id='$owner' AND deleted_at IS NOT NULL"))
            // The new adapter may still explicitly delete these tables after the trigger; both paths are idempotent.
            for (table in listOf("user_achievement_featured", "user_achievement_awards", "user_note_activity_days")) {
                assertEquals(0, connection.sql("DELETE FROM $table WHERE user_id='$owner'"))
            }
        }
    }

    @Test fun `롤백하면 활성 계정과 모든 업적 기록을 원자적으로 복원한다`() = database { schema ->
        connection(schema).use { setup ->
            val owner = setup.owner()
            connection(schema).use { legacy ->
                legacy.autoCommit = false
                try {
                    legacy.sql("UPDATE users SET deleted_at=now() WHERE id='$owner'")
                    legacy.assertEvidence(owner, 0)
                    assertEquals(0, legacy.count("SELECT count(*) FROM users WHERE id='$owner' AND deleted_at IS NULL"))
                } finally { legacy.rollback() }
            }
            setup.assertEvidence(owner, 1)
            assertEquals(1, setup.count("SELECT count(*) FROM users WHERE id='$owner' AND deleted_at IS NULL"))
        }
    }

    @Test fun `활성 프로필 수정과 삭제 대상 없음과 반복 탈퇴는 안전하다`() = database { schema ->
        connection(schema).use { connection ->
            val owner = connection.owner()
            val other = connection.owner()
            connection.sql("UPDATE users SET display_name='edited profile' WHERE id='$owner'")
            connection.sql("UPDATE users SET deleted_at=NULL WHERE id='$owner'")
            connection.assertEvidence(owner, 1)
            connection.sql("UPDATE users SET deleted_at=now() WHERE id='$owner'")
            connection.assertEvidence(owner, 0)
            connection.sql("UPDATE users SET deleted_at=now()+interval '1 day' WHERE id='$owner'")
            assertEquals(0, connection.sql("UPDATE users SET deleted_at=now() WHERE id='$owner' AND deleted_at IS NULL"))
            connection.assertEvidence(owner, 0)
            connection.assertEvidence(other, 1)
            assertEquals(1, connection.count("SELECT count(*) FROM users WHERE id='$owner' AND deleted_at IS NOT NULL"))
        }
    }
}
