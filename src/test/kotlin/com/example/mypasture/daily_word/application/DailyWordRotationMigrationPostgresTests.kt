package com.example.mypasture.daily_word.application

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** 실제 PostgreSQL에서 이전 INSERT와 게시 스냅샷의 V42.8 업그레이드 호환성을 검증합니다. */
@EnabledIfEnvironmentVariable(named = "DAILY_WORD_ROTATION_TEST_JDBC_URL", matches = ".+")
class DailyWordRotationMigrationPostgresTests {
    private val url get() = System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_URL")
    private val user get() = System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_USER") ?: "postgres"
    private val password get() = System.getenv("DAILY_WORD_ROTATION_TEST_JDBC_PASSWORD") ?: ""
    private fun migration(schema: String, target: String) = Flyway.configure().dataSource(url, user, password)
        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).cleanDisabled(false).load()

    private fun database(target: String, block: (String) -> Unit) {
        val schema = "rotation_upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        try { migration(schema, target).migrate(); block(schema) }
        finally { migration(schema, "latest").clean() }
    }

    private fun connection(schema: String) = DriverManager.getConnection(url, user, password).also {
        assertEquals("PostgreSQL", it.metaData.databaseProductName)
        it.createStatement().use { statement -> statement.execute("SET search_path TO $schema, public") }
    }

    private fun Connection.sql(value: String) = createStatement().use { it.executeUpdate(value) }

    /** 로테이션 필드를 모르는 기존 SQL의 필수 열만 사용합니다. */
    private fun Connection.legacy(date: String): UUID = UUID.randomUUID().also { id ->
        sql("""INSERT INTO daily_words(id,word_date,bible_version,book_code,book_name,chapter,verse,verse_text,
            meditation,action_question,model,prompt_version,prompt_tokens,estimated_cost_usd)
            VALUES ('$id',DATE '$date','KOR1910','GEN','창세기',1,7,'원래 첫절 스냅샷','기존 안내문','기존 질문',
            'legacy-model','legacy-prompt',17,0.00023)""")
    }

    private fun Connection.assertLegacy(id: UUID) = createStatement().use { statement ->
        statement.executeQuery("SELECT * FROM daily_words WHERE id='$id'").use { row ->
            assertTrue(row.next())
            assertEquals("원래 첫절 스냅샷", row.getString("verse_text"))
            assertEquals("기존 안내문", row.getString("meditation"))
            assertEquals("legacy-model", row.getString("model"))
            assertEquals("legacy-prompt", row.getString("prompt_version"))
            assertEquals(17, row.getInt("prompt_tokens"))
            assertEquals(0, row.getBigDecimal("estimated_cost_usd").compareTo("0.00023".toBigDecimal()))
            assertEquals("AI", row.getString("source"))
            assertNull(row.getObject("end_verse"))
            assertNull(row.getString("passage_text"))
            assertEquals("", row.getString("topic_tags"))
            assertNull(row.getString("reading_mode"))
            assertNull(row.getString("passage_type"))
            assertNull(row.getString("rotation_entry_id"))
            assertFalse(row.next())
        }
    }

    @Test
    fun `upgrade preserves earlier snapshots and supports old SQL after 42 point 8`() = database("42.7") { schema ->
        connection(schema).use { connection ->
            val earlier = connection.legacy("2026-10-04")
            migration(schema, "42.8").migrate()
            connection.assertLegacy(earlier)
            connection.assertLegacy(connection.legacy("2026-10-05"))
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id FROM daily_word_rotation_lock").use { row ->
                    assertTrue(row.next()); assertEquals(1, row.getInt(1)); assertFalse(row.next())
                }
            }
        }
    }

    @Test
    fun `date uniqueness and valid nullable range remain enforced on actual PostgreSQL`() = database("42.8") { schema ->
        connection(schema).use { connection ->
            val id = connection.legacy("2026-10-05")
            assertEquals("23505", assertThrows(SQLException::class.java) { connection.legacy("2026-10-05") }.sqlState)
            assertEquals("23514", assertThrows(SQLException::class.java) {
                connection.sql("UPDATE daily_words SET end_verse=6 WHERE id='$id'")
            }.sqlState)
            connection.sql("UPDATE daily_words SET end_verse=7,reading_mode='CONTINUOUS',passage_type='NARRATIVE' WHERE id='$id'")
            assertEquals("23514", assertThrows(SQLException::class.java) {
                connection.sql("UPDATE daily_words SET reading_mode='INVALID' WHERE id='$id'")
            }.sqlState)
            assertEquals("23514", assertThrows(SQLException::class.java) {
                connection.sql("UPDATE daily_words SET passage_type='INVALID' WHERE id='$id'")
            }.sqlState)
        }
    }
}
