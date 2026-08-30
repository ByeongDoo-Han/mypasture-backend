package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.BibleRetrievalPort
import com.example.mypasture.ai.application.TextEmbeddingPort
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.BibleRetrievalResult
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.ai.domain.UnderstoodBibleQuestion
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.config.AiProperties
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

/** 질문 embedding으로 pgvector Top-K를 검색하고 DB의 실제 성경 절을 근거로 반환합니다. */
@Repository
class JdbcBibleRetrievalAdapter(
    private val jdbcTemplate: JdbcTemplate,
    private val embeddingPort: TextEmbeddingPort,
    private val bookRepository: BibleBookEntityRepository,
    private val verseRepository: BibleVerseEntityRepository,
    private val properties: AiProperties,
) : BibleRetrievalPort {
    private val postgres by lazy {
        jdbcTemplate.execute(ConnectionCallback { connection -> connection.metaData.databaseProductName })
            .orEmpty()
            .contains("PostgreSQL", ignoreCase = true)
    }
    @Volatile private var readiness = VectorIndexReadiness(false, 0)

    override suspend fun searchRelevantVerses(question: UnderstoodBibleQuestion, limit: Int): BibleRetrievalResult {
        val requestedLimit = limit.coerceAtMost(properties.ragTopK)
        require(requestedLimit > 0) { "RAG result limit must be greater than zero." }

        searchExactReference(question)?.let { return it }

        if (postgres && hasVectorIndex()) {
            try {
                val embedding = embeddingPort.embed(listOf(question.searchQuery))
                val citations = searchByVector(embedding.vectors.single(), question, requestedLimit)
                if (citations.isNotEmpty() || !properties.ragLexicalFallbackEnabled) {
                    return BibleRetrievalResult(
                        citations = citations,
                        embeddingTokens = embedding.promptTokens,
                        embeddingCostUsd = embedding.estimatedCostUsd,
                        strategy = RagRetrievalStrategy.PGVECTOR,
                    )
                }
            } catch (error: AiProviderException) {
                if (!properties.ragLexicalFallbackEnabled) throw error
                logger.warn("Vector RAG request failed; using configured lexical fallback", error)
            }
        }

        if (!properties.ragLexicalFallbackEnabled) return BibleRetrievalResult(emptyList())
        return BibleRetrievalResult(
            citations = searchLexically(question, requestedLimit),
            strategy = RagRetrievalStrategy.LEXICAL_FALLBACK,
        )
    }

    private fun hasVectorIndex(): Boolean {
        val now = System.nanoTime()
        val current = readiness
        if (now - current.checkedAtNanos < READINESS_CACHE_NANOS) return current.ready

        val ready = jdbcTemplate.queryForObject(
            """
                select count(embedding.id) > 0 and count(embedding.id) = (
                  select count(*) from bible_verses verse_count where verse_count.version = ?
                )
                from bible_embeddings embedding
                join bible_verses verse on verse.id = embedding.verse_id
                where embedding.embedding_model = ?
                  and embedding.dimensions = ?
                  and embedding.content_hash is not null
                  and verse.version = ?
            """.trimIndent(),
            Boolean::class.java,
            VERSION,
            properties.embeddingModel,
            properties.embeddingDimensions,
            VERSION,
        ) ?: false
        readiness = VectorIndexReadiness(ready, now)
        return ready
    }

    private fun searchByVector(vector: FloatArray, question: UnderstoodBibleQuestion, limit: Int): List<BibleCitation> {
        require(vector.size == properties.embeddingDimensions) { "Query embedding dimensions do not match configuration." }
        val reference = question.reference
        return jdbcTemplate.query(
            VECTOR_SEARCH_SQL,
            { row, _ ->
                BibleCitation(
                    verseId = row.getLong("verse_id"),
                    version = row.getString("version"),
                    book = row.getString("book_name"),
                    chapter = row.getInt("chapter"),
                    verse = row.getInt("verse"),
                    text = row.getString("text"),
                    bookCode = row.getString("book_code"),
                )
            },
            PgVectorLiteral.from(vector),
            properties.embeddingModel,
            properties.embeddingDimensions,
            VERSION,
            reference?.bookCode,
            reference?.bookCode,
            reference?.chapter,
            reference?.chapter,
            properties.ragMinSimilarity,
            limit,
        )
    }

    private fun searchExactReference(question: UnderstoodBibleQuestion): BibleRetrievalResult? {
        val reference = question.reference ?: return null
        val chapter = reference.chapter ?: return null
        val verse = reference.verse ?: return null
        val book = bookRepository.findByVersionAndCode(VERSION, reference.bookCode)
            ?: return BibleRetrievalResult(emptyList(), strategy = RagRetrievalStrategy.DIRECT_REFERENCE)
        val citation = verseRepository.findByVersionAndBook_IdAndChapterAndVerse(VERSION, book.id, chapter, verse)
            ?.toCitation()
        return BibleRetrievalResult(
            citations = listOfNotNull(citation),
            strategy = RagRetrievalStrategy.DIRECT_REFERENCE,
        )
    }

    private fun searchLexically(question: UnderstoodBibleQuestion, limit: Int): List<BibleCitation> {
        val matches = linkedMapOf<Long, BibleCitation>()
        val reference = question.reference
        val book = reference?.let { bookRepository.findByVersionAndCode(VERSION, it.bookCode) }
        val chapterVerses = if (book != null && reference.chapter != null) {
            verseRepository.findAllByVersionAndBook_IdAndChapterOrderByVerse(VERSION, book.id, reference.chapter)
        } else {
            emptyList()
        }

        question.lexicalKeywords.forEach { keyword ->
            if (matches.size >= limit) return@forEach
            val candidates = when {
                chapterVerses.isNotEmpty() -> chapterVerses.filter { it.text.contains(keyword, ignoreCase = true) }
                book != null -> verseRepository.searchInBook(VERSION, book.id, keyword, PageRequest.of(0, limit)).content
                else -> verseRepository.search(VERSION, keyword, PageRequest.of(0, limit)).content
            }
            candidates.forEach { verse ->
                matches.putIfAbsent(verse.id, verse.toCitation())
            }
        }
        if (matches.isEmpty() && chapterVerses.isNotEmpty()) {
            chapterVerses.take(limit).forEach { matches[it.id] = it.toCitation() }
        }
        if (matches.isEmpty() && book != null && reference?.chapter == null) {
            verseRepository.findAllByVersionAndBook_IdAndChapterOrderByVerse(VERSION, book.id, 1)
                .take(limit)
                .forEach { matches[it.id] = it.toCitation() }
        }
        return matches.values.take(limit)
    }

    private fun BibleVerseEntity.toCitation() =
        BibleCitation(
            verseId = id,
            version = version,
            book = book.name,
            chapter = chapter,
            verse = verse,
            text = text,
            bookCode = book.code,
        )

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcBibleRetrievalAdapter::class.java)
        private const val VERSION = "KOR1910"
        private const val READINESS_CACHE_NANOS = 30_000_000_000L
        private const val VECTOR_SEARCH_SQL = """
            with query_vector as (select cast(? as vector) as embedding)
            select
              verse.id as verse_id,
              verse.version,
              book.code as book_code,
              book.name as book_name,
              verse.chapter,
              verse.verse,
              verse.text
            from bible_embeddings embedding
            join bible_verses verse on verse.id = embedding.verse_id
            join bible_books book on book.id = verse.book_id
            cross join query_vector
            where embedding.embedding_model = ?
              and embedding.dimensions = ?
              and verse.version = ?
              and (cast(? as varchar) is null or book.code = ?)
              and (cast(? as integer) is null or verse.chapter = ?)
              and 1 - (embedding.embedding <=> query_vector.embedding) >= ?
            order by embedding.embedding <=> query_vector.embedding,
                     book.display_order,
                     verse.chapter,
                     verse.verse
            limit ?
        """
    }

    private data class VectorIndexReadiness(val ready: Boolean, val checkedAtNanos: Long)
}

/** pgvector가 허용하는 유한한 float 배열만 SQL vector literal로 변환합니다. */
object PgVectorLiteral {
    fun from(vector: FloatArray): String {
        require(vector.isNotEmpty()) { "Vector must not be empty." }
        require(vector.all(Float::isFinite)) { "Vector contains a non-finite value." }
        return vector.joinToString(prefix = "[", postfix = "]", separator = ",")
    }
}
