package com.example.mypasture.ai.infrastructure

import com.example.mypasture.ai.application.AiProviderException
import com.example.mypasture.ai.application.BibleRetrievalPort
import com.example.mypasture.ai.application.TextEmbeddingPort
import com.example.mypasture.ai.domain.BibleCitation
import com.example.mypasture.ai.domain.BibleRetrievalResult
import com.example.mypasture.ai.domain.RagRetrievalStrategy
import com.example.mypasture.bible.domain.BibleVerseEntity
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
    private val verseRepository: BibleVerseEntityRepository,
    private val properties: AiProperties,
) : BibleRetrievalPort {
    private val postgres by lazy {
        jdbcTemplate.execute(ConnectionCallback { connection -> connection.metaData.databaseProductName })
            .orEmpty()
            .contains("PostgreSQL", ignoreCase = true)
    }
    @Volatile private var readiness = VectorIndexReadiness(false, 0)

    override fun searchRelevantVerses(question: String, limit: Int): BibleRetrievalResult {
        val requestedLimit = limit.coerceAtMost(properties.ragTopK)
        require(requestedLimit > 0) { "RAG result limit must be greater than zero." }

        if (postgres && hasVectorIndex()) {
            try {
                val embedding = embeddingPort.embed(listOf(question))
                val citations = searchByVector(embedding.vectors.single(), requestedLimit)
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

    private fun searchByVector(vector: FloatArray, limit: Int): List<BibleCitation> {
        require(vector.size == properties.embeddingDimensions) { "Query embedding dimensions do not match configuration." }
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
                )
            },
            PgVectorLiteral.from(vector),
            properties.embeddingModel,
            properties.embeddingDimensions,
            VERSION,
            properties.ragMinSimilarity,
            limit,
        )
    }

    private fun searchLexically(question: String, limit: Int): List<BibleCitation> {
        val matches = linkedMapOf<Long, BibleCitation>()
        extractSearchKeywords(question).forEach { keyword ->
            if (matches.size >= limit) return@forEach
            verseRepository.search(VERSION, keyword, PageRequest.of(0, limit)).content.forEach { verse ->
                matches.putIfAbsent(verse.id, verse.toCitation())
            }
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
        )

    private fun extractSearchKeywords(question: String): List<String> {
        val normalized = question.trim().lowercase()
        val directTokens = keywordRegex.findAll(normalized)
            .map { it.value }
            .filter { it.length >= 2 && it !in stopWords }
            .toMutableList()
        topicExpansions.forEach { (topic, expansions) ->
            if (normalized.contains(topic)) directTokens += expansions
        }
        return directTokens.distinct().take(MAX_KEYWORDS)
    }

    companion object {
        private val logger = LoggerFactory.getLogger(JdbcBibleRetrievalAdapter::class.java)
        private const val VERSION = "KOR1910"
        private const val MAX_KEYWORDS = 12
        private const val READINESS_CACHE_NANOS = 30_000_000_000L
        private val keywordRegex = Regex("[가-힣A-Za-z0-9]+")
        private val stopWords = setOf("오늘", "읽은", "말씀", "설명", "해주세요", "해줘", "위한", "대한", "나는", "제가", "입니다")
        private val topicExpansions = mapOf(
            "불안" to listOf("염려", "마음", "평강", "기도"),
            "염려" to listOf("염려", "평강", "기도"),
            "기도" to listOf("기도", "간구", "감사"),
            "감사" to listOf("감사", "기도"),
            "관계" to listOf("사랑", "용서", "서로"),
            "용서" to listOf("용서", "사랑"),
            "창세기" to listOf("태초", "창조"),
            "위로" to listOf("목자", "쉴", "평강"),
            "힘듭" to listOf("평강", "사랑", "위로"),
        )
        private const val VECTOR_SEARCH_SQL = """
            with query_vector as (select cast(? as vector) as embedding)
            select
              verse.id as verse_id,
              verse.version,
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
