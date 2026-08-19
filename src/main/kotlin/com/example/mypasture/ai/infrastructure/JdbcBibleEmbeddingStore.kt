package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.BibleEmbeddingStorePort
import com.example.mypasture.ai.domain.BibleEmbeddingSource
import java.sql.Connection
import javax.sql.DataSource
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** PostgreSQL advisory lock과 멱등 upsert로 성경 embedding index를 관리합니다. */
@Repository
class JdbcBibleEmbeddingStore(
    private val jdbcTemplate: JdbcTemplate,
    private val dataSource: DataSource,
) : BibleEmbeddingStorePort {
    private val postgres by lazy {
        jdbcTemplate.execute(ConnectionCallback { connection -> connection.metaData.databaseProductName })
            .orEmpty()
            .contains("PostgreSQL", ignoreCase = true)
    }

    override fun isAvailable(): Boolean = postgres

    override fun tryAcquireIndexLock(): AutoCloseable? {
        if (!postgres) return null
        val connection = dataSource.connection
        val acquired = connection.prepareStatement("select pg_try_advisory_lock(?)").use { statement ->
            statement.setLong(1, INDEX_LOCK_KEY)
            statement.executeQuery().use { result -> result.next() && result.getBoolean(1) }
        }
        if (!acquired) {
            connection.close()
            return null
        }
        return AdvisoryLock(connection)
    }

    override fun findPendingSources(model: String, dimensions: Int, limit: Int): List<BibleEmbeddingSource> =
        jdbcTemplate.query(
            PENDING_SOURCE_SQL,
            { row, _ ->
                BibleEmbeddingSource(
                    verseId = row.getLong("verse_id"),
                    content = row.getString("content"),
                    contentHash = row.getString("content_hash"),
                )
            },
            VERSION,
            model,
            dimensions,
            limit,
        )

    override fun upsert(
        model: String,
        dimensions: Int,
        sources: List<BibleEmbeddingSource>,
        vectors: List<FloatArray>,
    ) {
        require(sources.size == vectors.size) { "Embedding source and vector counts must match." }
        val rows = sources.zip(vectors)
        jdbcTemplate.batchUpdate(UPSERT_SQL, rows, rows.size.coerceAtLeast(1)) { statement, row ->
            val (source, vector) = row
            statement.setLong(1, source.verseId)
            statement.setString(2, model)
            statement.setInt(3, dimensions)
            statement.setString(4, PgVectorLiteral.from(vector))
            statement.setString(5, source.contentHash)
        }
    }

    override fun countTotalVerses(): Long =
        jdbcTemplate.queryForObject("select count(*) from bible_verses where version = ?", Long::class.java, VERSION) ?: 0

    override fun countIndexedVerses(model: String, dimensions: Int): Long =
        jdbcTemplate.queryForObject(
            FRESH_INDEX_COUNT_SQL,
            Long::class.java,
            model,
            dimensions,
            VERSION,
        ) ?: 0

    /** 전용 connection에 획득한 session advisory lock을 반드시 해제합니다. */
    private class AdvisoryLock(private val connection: Connection) : AutoCloseable {
        override fun close() {
            runCatching {
                connection.prepareStatement("select pg_advisory_unlock(?)").use { statement ->
                    statement.setLong(1, INDEX_LOCK_KEY)
                    statement.execute()
                }
            }
            connection.close()
        }
    }

    companion object {
        private const val VERSION = "KOR1910"
        private const val INDEX_LOCK_KEY = 1_910_067L
        private const val SOURCE_HASH_SQL = "encode(digest(concat_ws('|', verse.version, book.code, book.name, verse.chapter::text, verse.verse::text, verse.text), 'sha256'), 'hex')"
        private const val PENDING_SOURCE_SQL = """
            with sources as (
              select
                verse.id as verse_id,
                concat(book.name, ' ', verse.chapter, ':', verse.verse, ' ', verse.text) as content,
                $SOURCE_HASH_SQL as content_hash
              from bible_verses verse
              join bible_books book on book.id = verse.book_id
              where verse.version = ?
            )
            select sources.verse_id, sources.content, sources.content_hash
            from sources
            left join bible_embeddings embedding
              on embedding.verse_id = sources.verse_id
             and embedding.embedding_model = ?
             and embedding.dimensions = ?
            where embedding.id is null or embedding.content_hash is distinct from sources.content_hash
            order by sources.verse_id
            limit ?
        """
        private const val UPSERT_SQL = """
            insert into bible_embeddings(verse_id, embedding_model, dimensions, embedding, content_hash, created_at)
            values (?, ?, ?, cast(? as vector), ?, now())
            on conflict(verse_id, embedding_model, dimensions)
            do update set embedding = excluded.embedding,
                          content_hash = excluded.content_hash,
                          created_at = now()
        """
        private const val FRESH_INDEX_COUNT_SQL = """
            select count(*)
            from bible_verses verse
            join bible_books book on book.id = verse.book_id
            join bible_embeddings embedding
              on embedding.verse_id = verse.id
             and embedding.embedding_model = ?
             and embedding.dimensions = ?
            where verse.version = ?
              and embedding.content_hash = $SOURCE_HASH_SQL
        """
    }
}
