package com.example.mypasture.billing

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** 격리한 PostgreSQL 스키마에 결제 마이그레이션을 적용하고 DB 제약을 확인합니다. */
@Tag("billing-postgres")
class BillingMigrationPostgresTests {
    @Test
    fun `V42 and V43 apply once and reject invalid or duplicate purchases`() {
        val url = "jdbc:postgresql://127.0.0.1:55439/mypasture_billing_test"
        val schema = "billing_migration_" + UUID.randomUUID().toString().replace("-", "")
        DriverManager.getConnection(url, "billing_test", "billing_test_only").use { connection ->
            connection.createStatement().use { sql ->
                sql.execute("CREATE SCHEMA $schema")
                try {
                    sql.execute("SET search_path TO $schema")
                    sql.execute("CREATE TABLE users (id UUID PRIMARY KEY)")
                    val flyway = Flyway.configure().dataSource(url, "billing_test", "billing_test_only")
                        .schemas(schema).defaultSchema(schema).baselineOnMigrate(true).baselineVersion("41").load()
                    assertEquals(2, flyway.migrate().migrationsExecuted)
                    assertEquals(0, flyway.migrate().migrationsExecuted)
                    flyway.validate()
                    val userId = UUID.randomUUID()
                    sql.execute("INSERT INTO users VALUES ('$userId')")
                    fun insert(transaction: String, units: Int, owner: UUID = userId) = """
                        INSERT INTO billing_purchases
                        (id,user_id,store,environment,transaction_key,product,financial_status,access_revoked,
                         auto_renew,entitled_units,revision,created_at,updated_at)
                        VALUES ('${UUID.randomUUID()}','$owner','APPLE','SANDBOX','$transaction','DEEP_THREE_SESSIONS',
                         'PAID',false,false,$units,1,now(),now())
                    """.trimIndent()
                    sql.execute(insert("first", 3))
                    assertEquals("23505", assertThrows(SQLException::class.java) { sql.execute(insert("first", 3)) }.sqlState)
                    assertEquals("23514", assertThrows(SQLException::class.java) { sql.execute(insert("invalid-units", 4)) }.sqlState)
                    assertEquals("23503", assertThrows(SQLException::class.java) { sql.execute(insert("unknown-owner", 3, UUID.randomUUID())) }.sqlState)
                    val accountToken = UUID.randomUUID()
                    sql.execute("INSERT INTO billing_accounts (user_id, account_token) VALUES ('$userId', '$accountToken')")
                    val anotherUser = UUID.randomUUID()
                    sql.execute("INSERT INTO users VALUES ('$anotherUser')")
                    assertEquals("23505", assertThrows(SQLException::class.java) {
                        sql.execute("INSERT INTO billing_accounts (user_id, account_token) VALUES ('$anotherUser', '$accountToken')")
                    }.sqlState)
                    val requestId = UUID.randomUUID()
                    fun insertJob() = """
                        INSERT INTO billing_verification_jobs
                        (id,user_id,request_id,store,environment,product_id,request_hash,encrypted_proof,phase,next_attempt_at,created_at,updated_at)
                        VALUES ('${UUID.randomUUID()}','$userId','$requestId','GOOGLE','SANDBOX','monthly','hash','encrypted','VERIFY',now(),now(),now())
                    """.trimIndent()
                    sql.execute(insertJob())
                    assertEquals("23505", assertThrows(SQLException::class.java) { sql.execute(insertJob()) }.sqlState)
                } finally {
                    sql.execute("SET search_path TO public")
                    sql.execute("DROP SCHEMA $schema CASCADE")
                }
            }
        }
    }
}
