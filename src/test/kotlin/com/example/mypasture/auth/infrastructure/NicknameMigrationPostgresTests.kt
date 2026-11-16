package com.example.mypasture.auth.infrastructure

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** 실제 PostgreSQL에서 기존 닉네임 보존과 신규 닉네임의 영속적 중복 방지를 검증합니다. */
@EnabledIfEnvironmentVariable(named = "NICKNAME_TEST_JDBC_URL", matches = ".+")
class NicknameMigrationPostgresTests {
    private val jdbcUrl get() = System.getenv("NICKNAME_TEST_JDBC_URL")
    private val jdbcUser get() = System.getenv("NICKNAME_TEST_JDBC_USER") ?: "postgres"
    private val jdbcPassword get() = System.getenv("NICKNAME_TEST_JDBC_PASSWORD") ?: ""

    private fun database(test: (String) -> Unit) {
        val schema = "nickname_${UUID.randomUUID().toString().replace("-", "") }"
        val flyway = Flyway.configure().dataSource(jdbcUrl, jdbcUser, jdbcPassword)
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").cleanDisabled(false).load()
        try {
            flyway.migrate()
            test(schema)
        } finally {
            flyway.clean()
        }
    }

    private fun connection(schema: String): Connection = DriverManager.getConnection(jdbcUrl, jdbcUser, jdbcPassword).also {
        assertEquals("PostgreSQL", it.metaData.databaseProductName)
        it.createStatement().use { statement -> statement.execute("SET search_path TO $schema, public") }
    }

    private fun Connection.insert(displayName: String, nicknameKey: String? = null): UUID {
        val id = UUID.randomUUID()
        prepareStatement("INSERT INTO users(id, display_name, nickname_key) VALUES (?, ?, ?)").use { statement ->
            statement.setObject(1, id)
            statement.setString(2, displayName)
            statement.setString(3, nicknameKey)
            statement.executeUpdate()
        }
        return id
    }

    @Test
    fun `마이그레이션은 기존 이름을 보존하고 출시 전 중복 키를 거부한다`() = database { schema ->
        connection(schema).use { connection ->
            connection.insert("기존 이름")
            connection.insert("기존 이름")
            val owner = connection.insert("친절한고양이", "친절한고양이")
            val conflict = assertThrows(SQLException::class.java) {
                connection.insert("다른표시", "친절한고양이")
            }
            assertEquals("23505", conflict.sqlState)

            connection.prepareStatement("UPDATE users SET nickname_key = NULL, deleted_at = now() WHERE id = ?").use { statement ->
                statement.setObject(1, owner)
                assertEquals(1, statement.executeUpdate())
            }
            connection.insert("새로운고양이", "친절한고양이")
        }
    }
}
