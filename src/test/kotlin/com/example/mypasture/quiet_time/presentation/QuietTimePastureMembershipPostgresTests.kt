package com.example.mypasture.quiet_time.presentation

import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/** PostgreSQL/Flyway에서 한 계정 한 목장과 가입 요청 본명 삭제 제약을 검증합니다. */
@EnabledIfEnvironmentVariable(named = "PASTURE_TEST_JDBC_URL", matches = ".+")
class QuietTimePastureMembershipPostgresTests {
    @Test
    fun `마이그레이션은 목장 소속을 하나로 제한하고 대기 요청에만 본명을 보관한다`() {
        val url = System.getenv("PASTURE_TEST_JDBC_URL")
        val user = System.getenv("PASTURE_TEST_JDBC_USER") ?: "postgres"
        val password = System.getenv("PASTURE_TEST_JDBC_PASSWORD") ?: ""
        val schema = "pasture_${UUID.randomUUID().toString().replace("-", "") }"
        val flyway = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
            .locations("classpath:db/migration").cleanDisabled(false).load()
        val leader = UUID.randomUUID()
        val applicant = UUID.randomUUID()
        val firstPasture = UUID.randomUUID()
        val secondPasture = UUID.randomUUID()
        try {
            Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .locations("classpath:db/migration").target("42.14").load().migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { it.execute("set search_path to $schema, public") }
                connection.createStatement().use { statement ->
                    statement.execute("insert into users(id, display_name) values ('$leader', '목자'), ('$applicant', '목원')")
                    statement.execute("insert into quiet_time_pastures(id, name, leader_name, leader_user_id, invite_code, created_at) values ('$firstPasture', '첫 목장', '목자', '$leader', 'FIRSTPASTURE', now() - interval '1 day'), ('$secondPasture', '둘째 목장', '목자', '$leader', 'SECONDPAS', now())")
                }
            }
            flyway.migrate()
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { it.execute("set search_path to $schema, public") }
                connection.createStatement().use { statement ->
                    statement.executeQuery("select pasture_id from quiet_time_pasture_memberships where user_id = '$leader'").use { rows ->
                        rows.next()
                        assertEquals(secondPasture, rows.getObject(1, UUID::class.java))
                    }
                }
                connection.createStatement().use { statement ->
                    statement.execute("insert into quiet_time_pasture_memberships(user_id, pasture_id, role) values ('$applicant', '$firstPasture', 'MEMBER')")
                }
                val duplicate = assertFailsWith<SQLException> {
                    connection.createStatement().use { it.execute("insert into quiet_time_pasture_memberships(user_id, pasture_id, role) values ('$applicant', '$secondPasture', 'MEMBER')") }
                }
                assertEquals("23505", duplicate.sqlState)

                val request = UUID.randomUUID()
                connection.createStatement().use { statement ->
                    statement.execute("insert into quiet_time_pasture_join_requests(id, pasture_id, user_id, active_user_id, real_name, status, created_at, expires_at) values ('$request', '$firstPasture', '$applicant', '$applicant', '홍길동', 'PENDING', now(), now() + interval '7 days')")
                }
                val invalid = assertFailsWith<SQLException> {
                    connection.createStatement().use { it.execute("update quiet_time_pasture_join_requests set status = 'APPROVED' where id = '$request'") }
                }
                assertEquals("23514", invalid.sqlState)
                connection.createStatement().use { statement ->
                    statement.execute("update quiet_time_pasture_join_requests set status = 'APPROVED', active_user_id = null, real_name = null, decided_at = now() where id = '$request'")
                    statement.executeQuery("select real_name from quiet_time_pasture_join_requests where id = '$request'").use { rows ->
                        rows.next()
                        assertEquals(null, rows.getString(1))
                    }
                }
            }
        } finally {
            flyway.clean()
        }
    }
}
