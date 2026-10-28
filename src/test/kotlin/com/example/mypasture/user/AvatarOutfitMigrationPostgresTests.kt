package com.example.mypasture.user

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.assertEquals

/** 실제 PostgreSQL에서 V42.13만 기존 버전 이후로 적용하고 제약·롤백을 확인합니다. */
class AvatarOutfitMigrationPostgresTests {
    @Test fun `복장 마이그레이션은 기본 스키마 뒤에 적용되고 계정 유일성을 유지한다`() {
        val url = System.getenv("AVATAR_OUTFIT_MIGRATION_JDBC_URL")
        assumeTrue(!url.isNullOrBlank(), "PostgreSQL URL is required for this suite")
        val user = System.getenv("AVATAR_OUTFIT_MIGRATION_JDBC_USER") ?: "postgres"
        val password = System.getenv("AVATAR_OUTFIT_MIGRATION_JDBC_PASSWORD") ?: ""
        val schema = "avatar_outfit_${UUID.randomUUID().toString().replace("-", "")}".lowercase()
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        DriverManager.getConnection(url, user, password).use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("create schema $schema")
                statement.execute("create table $schema.users (id uuid primary key)")
                statement.execute("insert into $schema.users (id) values ('$first'), ('$second')")
            }
        }
        try {
            val flyway = Flyway.configure().dataSource(url, user, password).schemas(schema).defaultSchema(schema)
                .baselineOnMigrate(true).baselineVersion("42.12").load()
            val result = flyway.migrate()
            assertEquals("42.13", result.targetSchemaVersion)
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("insert into $schema.user_avatar_outfits (user_id, hat_code) values ('$first', 'STRAW_HAT')")
                    val count = statement.executeQuery("select count(*) from $schema.user_avatar_outfits where user_id = '$first'")
                    count.next()
                    assertEquals(1, count.getInt(1))
                }
                connection.autoCommit = false
                connection.createStatement().use { statement ->
                    statement.execute("insert into $schema.user_avatar_outfits (user_id, hat_code) values ('$second', 'GREEN_BERET')")
                }
                connection.rollback()
                connection.createStatement().use { statement ->
                    val count = statement.executeQuery("select count(*) from $schema.user_avatar_outfits where user_id = '$second'")
                    count.next()
                    assertEquals(0, count.getInt(1))
                }
            }
        } finally {
            DriverManager.getConnection(url, user, password).use { connection ->
                connection.createStatement().use { statement -> statement.execute("drop schema if exists $schema cascade") }
            }
        }
    }
}
