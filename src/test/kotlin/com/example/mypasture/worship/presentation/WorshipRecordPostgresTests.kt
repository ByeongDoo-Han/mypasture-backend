package com.example.mypasture.worship.presentation

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** 실제 PostgreSQL/Flyway에서 50절 경계와 사용자 삭제 전파를 검증합니다. */
@EnabledIfEnvironmentVariable(named = "WORSHIP_TEST_JDBC_URL", matches = ".+")
class WorshipRecordPostgresTests {
    @Test
    fun `마이그레이션은 50절을 허용하고 51절을 거부하며 소유자 데이터를 삭제한다`() {
        val url = System.getenv("WORSHIP_TEST_JDBC_URL")
        val user = System.getenv("WORSHIP_TEST_JDBC_USER")
        val password = System.getenv("WORSHIP_TEST_JDBC_PASSWORD")
        val schema = "worship_${UUID.randomUUID().toString().replace("-", "")}"
        val flyway = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").cleanDisabled(false).load()
        try {
            flyway.migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { it.execute("set search_path to $schema, public") }
                val owner = UUID.randomUUID()
                connection.createStatement().use { it.execute("insert into users(id, display_name) values ('$owner', 'worship-test')") }
                fun insert(start: Int, end: Int) = connection.createStatement().use { statement ->
                    statement.execute("""insert into worship_records(user_id,worship_date,service_type,bible_version,book_code,book_name,chapter,start_verse,end_verse,heard)
                        values ('$owner',current_date,'SUNDAY','KOR1910','GEN','창세기',1,$start,$end,'기억한 말씀')""")
                }
                insert(1, 50)
                assertEquals("23514", assertThrows(SQLException::class.java) { insert(1, 51) }.sqlState)
                connection.createStatement().use { it.execute("delete from users where id='$owner'") }
                connection.createStatement().use { statement -> statement.executeQuery("select count(*) from worship_records where user_id='$owner'").use { result -> result.next(); assertEquals(0, result.getInt(1)) } }
            }
        } finally {
            flyway.clean()
        }
    }
}
