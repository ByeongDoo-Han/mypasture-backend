package com.example.mypasture.ai

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** 실제 PostgreSQL에서 구버전 삭제와 신규 문맥 보호 migration을 검증합니다. */
@EnabledIfEnvironmentVariable(named = "AI_HISTORY_TEST_JDBC_URL", matches = ".+")
class AiContextDeletionGuardPostgresTests {
    private fun connection(schema: String): Connection = DriverManager.getConnection(
        System.getenv("AI_HISTORY_TEST_JDBC_URL"), System.getenv("AI_HISTORY_TEST_JDBC_USER"),
        System.getenv("AI_HISTORY_TEST_JDBC_PASSWORD"),
    ).also { it.createStatement().use { stmt -> stmt.execute("SET search_path TO $schema, public") } }

    private fun database(test: (String, Flyway) -> Unit) {
        val schema = "context_guard_${UUID.randomUUID().toString().replace("-", "")}"
        val flyway = Flyway.configure().dataSource(System.getenv("AI_HISTORY_TEST_JDBC_URL"),
            System.getenv("AI_HISTORY_TEST_JDBC_USER"), System.getenv("AI_HISTORY_TEST_JDBC_PASSWORD"))
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
            .cleanDisabled(false).load()
        try { flyway.migrate(); test(schema, flyway) } finally { flyway.clean() }
    }

    private fun Connection.sql(value: String) = createStatement().use { it.execute(value) }
    private fun Connection.count(value: String): Int = createStatement().use { stmt ->
        stmt.executeQuery(value).use { rs -> rs.next(); rs.getInt(1) }
    }
    private fun Connection.user(): UUID = UUID.randomUUID().also { id ->
        sql("INSERT INTO users(id, display_name) VALUES ('$id', 'guard-test')")
    }
    private fun Connection.history(user: UUID): UUID = UUID.randomUUID().also { id ->
        sql("""INSERT INTO ai_answer_history(turn_id,user_id,question_ciphertext,response_ciphertext,
            assistant_version,response_schema_version,created_at,context_snapshot_ciphertext,context_snapshot_expires_at)
            VALUES ('$id','$user','opaque-question','opaque-answer','test','1',now(),'opaque-snapshot',now()+interval '1 day')""")
    }
    private fun Connection.conversation(user: UUID) {
        sql("""INSERT INTO ai_conversations(id,user_id,plan_snapshot,status,encrypted_context,expires_at)
            VALUES ('${UUID.randomUUID()}','$user','FREE','ACTIVE','opaque-context',now()+interval '1 day')""")
    }

    @Test fun `legacy deletion invalidates owner contexts but preserves unrelated answers and zero row retry`() = database { schema, _ ->
        connection(schema).use { c ->
            val owner=c.user(); val other=c.user(); val root=c.history(owner); c.history(owner); c.history(other)
            c.conversation(owner); c.conversation(other)
            c.sql("DELETE FROM ai_answer_history WHERE turn_id='$root'")
            assertEquals(1,c.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$owner'"))
            assertEquals(0,c.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$owner' AND context_snapshot_ciphertext IS NOT NULL"))
            assertEquals(0,c.count("SELECT count(*) FROM ai_conversations WHERE user_id='$owner'"))
            assertEquals(1,c.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$other' AND context_snapshot_ciphertext IS NOT NULL"))
            c.conversation(owner); c.history(owner)
            c.sql("DELETE FROM ai_answer_history WHERE turn_id='$root'")
            assertEquals(1,c.count("SELECT count(*) FROM ai_conversations WHERE user_id='$owner'"))
            assertEquals(1,c.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$owner' AND context_snapshot_ciphertext IS NOT NULL"))
        }
    }

    @Test fun `legacy lock inversion fails atomically with NOWAIT and succeeds on retry`() = database { schema, _ ->
        connection(schema).use { setup ->
            val owner=setup.user(); val root=setup.history(owner); setup.history(owner); setup.conversation(owner)
            connection(schema).use { current -> connection(schema).use { legacy ->
                current.autoCommit=false; legacy.autoCommit=false
                current.sql("SELECT id FROM users WHERE id='$owner' FOR UPDATE")
                legacy.sql("SELECT turn_id FROM ai_answer_history WHERE turn_id='$root' FOR UPDATE")
                val failure=assertThrows(SQLException::class.java) { legacy.sql("DELETE FROM ai_answer_history WHERE turn_id='$root'") }
                assertEquals("55P03",failure.sqlState)
                legacy.rollback()
                assertEquals(2,setup.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$owner' AND context_snapshot_ciphertext IS NOT NULL"))
                assertEquals(1,setup.count("SELECT count(*) FROM ai_conversations WHERE user_id='$owner'"))
                current.rollback()
                legacy.sql("DELETE FROM ai_answer_history WHERE turn_id='$root'"); legacy.commit()
                assertEquals(0,setup.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$owner' AND context_snapshot_ciphertext IS NOT NULL"))
            } }
        }
    }

    @Test fun `multi owner delete and user cascade invalidate only affected owners`() = database { schema, _ ->
        connection(schema).use { c ->
            val a=c.user(); val b=c.user(); val other=c.user()
            val ar=c.history(a); c.history(a); val br=c.history(b); c.history(b); c.history(other)
            c.conversation(a); c.conversation(b); c.conversation(other)
            c.sql("DELETE FROM ai_answer_history WHERE turn_id IN ('$ar','$br')")
            assertEquals(0,c.count("SELECT count(*) FROM ai_answer_history WHERE user_id IN ('$a','$b') AND context_snapshot_ciphertext IS NOT NULL"))
            assertEquals(1,c.count("SELECT count(*) FROM ai_conversations"))
            c.sql("DELETE FROM users WHERE id='$a'")
            assertEquals(0,c.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$a'"))
            assertEquals(1,c.count("SELECT count(*) FROM ai_answer_history WHERE user_id='$other' AND context_snapshot_ciphertext IS NOT NULL"))
        }
    }

    @Test fun `bulk legacy delete rolls back every owner if later user lock is unavailable`() = database { schema, _ ->
        connection(schema).use { setup ->
            val owners=listOf(setup.user(),setup.user()).sortedBy { it.toString() }
            val roots=owners.map { setup.history(it) }
            owners.forEach { setup.history(it); setup.conversation(it) }
            connection(schema).use { blocker -> connection(schema).use { legacy ->
                blocker.autoCommit=false; legacy.autoCommit=false
                blocker.sql("SELECT id FROM users WHERE id='${owners.last()}' FOR UPDATE")
                val filter=roots.joinToString(",") { "'$it'" }
                val failure=assertThrows(SQLException::class.java) { legacy.sql("DELETE FROM ai_answer_history WHERE turn_id IN ($filter)") }
                assertEquals("55P03",failure.sqlState); legacy.rollback()
                assertEquals(4,setup.count("SELECT count(*) FROM ai_answer_history WHERE context_snapshot_ciphertext IS NOT NULL"))
                assertEquals(2,setup.count("SELECT count(*) FROM ai_conversations"))
                blocker.rollback()
                legacy.sql("DELETE FROM ai_answer_history WHERE turn_id IN ($filter)"); legacy.commit()
                assertEquals(2,setup.count("SELECT count(*) FROM ai_answer_history"))
                assertEquals(0,setup.count("SELECT count(*) FROM ai_answer_history WHERE context_snapshot_ciphertext IS NOT NULL"))
                assertEquals(0,setup.count("SELECT count(*) FROM ai_conversations"))
            } }
        }
    }

    @Test fun `overlapping legacy multi owner deletes rollback deadlock victim without partial success`() = database { schema, _ ->
        connection(schema).use { setup ->
            val a=setup.user(); val b=setup.user()
            val ar=setup.history(a); setup.history(a); val br=setup.history(b); setup.history(b)
            setup.conversation(a); setup.conversation(b)
            val barrier=java.util.concurrent.CyclicBarrier(2)
            val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                fun delete(first: UUID, second: UUID)=pool.submit<String> {
                    connection(schema).use { c ->
                        c.autoCommit=false
                        c.sql("SET LOCAL statement_timeout='8s'")
                        try {
                            c.sql("DELETE FROM ai_answer_history WHERE turn_id='$first'")
                            barrier.await(5,java.util.concurrent.TimeUnit.SECONDS)
                            c.sql("DELETE FROM ai_answer_history WHERE turn_id='$second'")
                            c.commit(); "COMMITTED"
                        } catch (e: SQLException) { c.rollback(); e.sqlState }
                    }
                }
                val first=delete(ar,br); val second=delete(br,ar)
                assertEquals(setOf("COMMITTED","40P01"),setOf(first.get(12,java.util.concurrent.TimeUnit.SECONDS),second.get(12,java.util.concurrent.TimeUnit.SECONDS)))
                assertEquals(2,setup.count("SELECT count(*) FROM ai_answer_history"))
                assertEquals(0,setup.count("SELECT count(*) FROM ai_answer_history WHERE context_snapshot_ciphertext IS NOT NULL"))
                assertEquals(0,setup.count("SELECT count(*) FROM ai_conversations"))
            } finally { pool.shutdownNow() }
        }
    }

    @Test fun `upgrade from 42 point 3 backfills ranges and preserves existing content`() {
        val schema="upgrade_${UUID.randomUUID().toString().replace("-", "")}"
        fun migration(target: String)=Flyway.configure().dataSource(System.getenv("AI_HISTORY_TEST_JDBC_URL"),
            System.getenv("AI_HISTORY_TEST_JDBC_USER"),System.getenv("AI_HISTORY_TEST_JDBC_PASSWORD"))
            .schemas(schema).defaultSchema(schema).locations("classpath:db/migration").target(target).cleanDisabled(false).load()
        val latest=migration("latest")
        try {
            migration("42.3").migrate()
            val id=UUID.randomUUID()
            connection(schema).use { c -> val user=c.user()
                c.sql("""INSERT INTO scripture_copies(id,user_id,version,book_code,book_name,chapter,verse,source_text,typed_text,reflection,revision,status,created_at,updated_at)
                    VALUES ('$id','$user','KOR1910','GEN','창세기',1,2,'원문','원','묵상',3,'DRAFT',now(),now())""")
            }
            latest.migrate()
            connection(schema).use { c ->
                assertEquals(1,c.count("SELECT count(*) FROM scripture_copies WHERE id='$id' AND end_verse=verse AND revision=3 AND typed_text='원' AND reflection='묵상'"))
                val failure=assertThrows(SQLException::class.java) { c.sql("UPDATE scripture_copies SET end_verse=verse+20 WHERE id='$id'") }
                assertEquals("23514",failure.sqlState)
                val user=c.user(); val history=c.history(user)
                val pair=assertThrows(SQLException::class.java) { c.sql("UPDATE ai_answer_history SET context_snapshot_expires_at=NULL WHERE turn_id='$history'") }
                assertEquals("23514",pair.sqlState)
            }
        } finally { latest.clean() }
    }
}
