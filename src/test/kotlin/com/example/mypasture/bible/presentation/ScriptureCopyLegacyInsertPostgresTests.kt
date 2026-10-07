package com.example.mypasture.bible.presentation

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/** 이전 서버 SQL과 새 범위 INSERT를 실제 PostgreSQL migration에서 검증합니다. */
@EnabledIfEnvironmentVariable(named = "SCRIPTURE_COPY_TEST_JDBC_URL", matches = ".+")
class ScriptureCopyLegacyInsertPostgresTests {
    private fun migration(schema: String, target: String) = Flyway.configure()
        .dataSource(System.getenv("SCRIPTURE_COPY_TEST_JDBC_URL"), System.getenv("SCRIPTURE_COPY_TEST_JDBC_USER"),
            System.getenv("SCRIPTURE_COPY_TEST_JDBC_PASSWORD"))
        .schemas(schema).defaultSchema(schema).locations("classpath:db/migration")
        .target(target).cleanDisabled(false).load()

    private fun database(target: String, block: (String) -> Unit) {
        val schema="copy_legacy_${UUID.randomUUID().toString().replace("-", "")}"
        try { migration(schema,target).migrate(); block(schema) }
        finally { migration(schema,"latest").clean() }
    }

    private fun connection(schema: String) = DriverManager.getConnection(System.getenv("SCRIPTURE_COPY_TEST_JDBC_URL"),
        System.getenv("SCRIPTURE_COPY_TEST_JDBC_USER"),System.getenv("SCRIPTURE_COPY_TEST_JDBC_PASSWORD"))
        .also { it.createStatement().use { stmt -> stmt.execute("SET search_path TO $schema, public") } }

    private fun Connection.sql(statement: String) = createStatement().use { it.execute(statement) }
    private fun Connection.owner()=UUID.randomUUID().also { sql("INSERT INTO users(id,display_name) VALUES ('$it','legacy-copy-test')") }
    private fun Connection.copy(owner: UUID, verse: Int, end: String? = null): UUID {
        val id=UUID.randomUUID()
        val endColumn=if(end==null) "" else ",end_verse"
        val endValue=if(end==null) "" else ",$end"
        sql("""INSERT INTO scripture_copies(id,user_id,version,book_code,book_name,chapter,verse,source_text,typed_text,
            reflection,revision,status,created_at,updated_at$endColumn)
            VALUES ('$id','$owner','KOR1910','GEN','창세기',1,$verse,'검증 원문','검증','기존 묵상',7,'DRAFT',now(),now()$endValue)""")
        return id
    }
    private fun Connection.assertRecord(id: UUID, start: Int, end: Int) {
        createStatement().use { stmt -> stmt.executeQuery("SELECT * FROM scripture_copies WHERE id='$id'").use { rs ->
            assertTrue(rs.next()); assertEquals(start,rs.getInt("verse")); assertEquals(end,rs.getInt("end_verse"))
            assertEquals("검증 원문",rs.getString("source_text")); assertEquals("검증",rs.getString("typed_text"))
            assertEquals("기존 묵상",rs.getString("reflection")); assertEquals(7,rs.getLong("revision"))
            assertEquals("DRAFT",rs.getString("status")); assertNull(rs.getTimestamp("completed_at")); assertFalse(rs.next())
        } }
    }

    @Test fun `42 point 5 legacy insert defect is fixed by upgrade without changing existing ranges`() = database("42.5") { schema ->
        connection(schema).use { c ->
            val owner=c.owner(); val range=c.copy(owner,2,"5")
            val failure=assertThrows(SQLException::class.java) { c.copy(owner,3) }
            assertEquals("23502",failure.sqlState)
            migration(schema,"latest").migrate()
            c.assertRecord(range,2,5)
            c.assertRecord(c.copy(owner,3),3,3)
            c.assertRecord(c.copy(owner,4,"NULL"),4,4)
            c.assertRecord(c.copy(owner,1,"20"),1,20)
            for(end in listOf("0","21")) {
                assertEquals("23514",assertThrows(SQLException::class.java) { c.copy(owner,1,end) }.sqlState)
            }
        }
    }

    @Test fun `clean latest schema supports old and new inserts while preserving update constraints`() = database("latest") { schema ->
        connection(schema).use { c ->
            val owner=c.owner(); c.assertRecord(c.copy(owner,7),7,7)
            val range=c.copy(owner,10,"12"); c.assertRecord(range,10,12)
            // 기존 행의 UPDATE는 보정하지 않아 명시적 NULL도 계속 거절합니다.
            assertEquals("23502",assertThrows(SQLException::class.java) { c.sql("UPDATE scripture_copies SET end_verse=NULL WHERE id='$range'") }.sqlState)
            c.assertRecord(range,10,12)
        }
    }
}
