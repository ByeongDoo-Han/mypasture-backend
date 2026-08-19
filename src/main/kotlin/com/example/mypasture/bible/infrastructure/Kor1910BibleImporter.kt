package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleBookEntity
import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import com.example.mypasture.bible.domain.BibleChapterEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import java.time.Clock
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.ConnectionCallback
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/** 애플리케이션 시작 시 1910 성경 원본을 한 번의 멱등 트랜잭션으로 DB에 동기화합니다. */
@Component
@ConditionalOnProperty(name = ["app.bible.import-enabled"], havingValue = "true")
class Kor1910BibleImporter(
    private val coordinator: BibleImportCoordinator,
) : ApplicationRunner {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun run(args: ApplicationArguments) {
        val summary = coordinator.importAll()
        if (!summary.lockAcquired) {
            log.info("KOR1910 Bible import skipped because another instance owns the import lock")
            return
        }
        log.info(
            "KOR1910 Bible import complete: books={}, chapters={}, available={}, empty={}, sourceMissing={}, verses={}, changedChapters={}",
            summary.books,
            summary.chapters,
            summary.availableChapters,
            summary.emptyChapters,
            summary.sourceMissingChapters,
            summary.verses,
            summary.changedChapters,
        )
    }
}

/** 다중 서버의 동시 import를 막고 기존 절 ID를 보존하며 권·장·절을 동기화합니다. */
@Service
class BibleImportCoordinator(
    private val parser: Kor1910HtmlBibleParser,
    private val bookRepository: BibleBookEntityRepository,
    private val chapterRepository: BibleChapterEntityRepository,
    private val verseRepository: BibleVerseEntityRepository,
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun importAll(): BibleImportSummary {
        parser.validateSourceDirectory()
        val postgres = isPostgresDatabase()
        if (!acquireImportLock(postgres)) return BibleImportSummary(lockAcquired = false)

        val books = synchronizeBooks()
        val chapters = chapterRepository.findAllByVersion(BibleCatalog.KOR1910_VERSION)
            .associateBy { ChapterKey(it.book.id, it.chapter) }
        var available = 0
        var empty = 0
        var sourceMissing = 0
        var verses = 0
        var changed = 0

        BibleCatalog.books.forEach { catalogBook ->
            val book = books.getValue(catalogBook.code)
            (1..catalogBook.chapterCount).forEach { chapterNumber ->
                val result = synchronizeChapter(
                    book,
                    catalogBook,
                    chapterNumber,
                    parser.readChapter(catalogBook, chapterNumber),
                    postgres,
                    chapters[ChapterKey(book.id, chapterNumber)],
                )
                when (result.status) {
                    BibleChapterContentStatus.AVAILABLE -> available++
                    BibleChapterContentStatus.EMPTY -> empty++
                    BibleChapterContentStatus.SOURCE_MISSING -> sourceMissing++
                }
                verses += result.verseCount
                if (result.changed) changed++
            }
        }

        return BibleImportSummary(
            books = books.size,
            chapters = available + empty + sourceMissing,
            availableChapters = available,
            emptyChapters = empty,
            sourceMissingChapters = sourceMissing,
            verses = verses,
            changedChapters = changed,
        )
    }

    private fun synchronizeBooks(): Map<String, BibleBookEntity> {
        val existing = bookRepository.findAllByVersionOrderByDisplayOrder(BibleCatalog.KOR1910_VERSION).associateBy { it.code }
        val synchronized = BibleCatalog.books.map { book ->
            existing[book.code]?.apply { synchronize(book) }
                ?: BibleBookEntity(
                    version = BibleCatalog.KOR1910_VERSION,
                    code = book.code,
                    name = book.name,
                    testament = book.testament,
                    displayOrder = book.displayOrder,
                )
        }
        return bookRepository.saveAllAndFlush(synchronized).associateBy { it.code }
    }

    private fun synchronizeChapter(
        book: BibleBookEntity,
        catalogBook: BibleBook,
        chapterNumber: Int,
        source: ParsedBibleChapterSource?,
        postgres: Boolean,
        existingChapter: BibleChapterEntity?,
    ): ChapterImportResult {
        if (source == null && existingChapter?.contentStatus == BibleChapterContentStatus.EMPTY) {
            return ChapterImportResult(BibleChapterContentStatus.EMPTY, 0, false)
        }
        if (source == null && existingChapter?.contentStatus == BibleChapterContentStatus.SOURCE_MISSING) {
            return ChapterImportResult(BibleChapterContentStatus.SOURCE_MISSING, existingChapter.verseCount, false)
        }
        if (
            source != null &&
            existingChapter?.sourceChecksum == source.checksum &&
            existingChapter.contentStatus == BibleChapterContentStatus.AVAILABLE &&
            existingChapter.verseCount == source.verses.size
        ) {
            return ChapterImportResult(BibleChapterContentStatus.AVAILABLE, existingChapter.verseCount, false)
        }

        val existingVerses = verseRepository.findAllByVersionAndBook_IdAndChapterOrderByVerse(
            BibleCatalog.KOR1910_VERSION,
            book.id,
            chapterNumber,
        )
        val chapter = existingChapter ?: BibleChapterEntity(
            version = BibleCatalog.KOR1910_VERSION,
            book = book,
            chapter = chapterNumber,
            contentStatus = BibleChapterContentStatus.EMPTY,
        )

        if (source == null) {
            val status = if (existingVerses.isEmpty()) BibleChapterContentStatus.EMPTY else BibleChapterContentStatus.SOURCE_MISSING
            val changed = chapter.id == 0L || chapter.contentStatus != status || chapter.verseCount != existingVerses.size
            if (changed) {
                chapter.mark(status, existingVerses.size, null, null, Instant.now(clock))
                chapterRepository.save(chapter)
            }
            return ChapterImportResult(status, existingVerses.size, changed)
        }

        val existingByNumber = existingVerses.associateBy { it.verse }
        val sourceNumbers = source.verses.map { it.verse }.toSet()
        val removedNumbers = existingByNumber.keys - sourceNumbers
        require(removedNumbers.isEmpty()) {
            "Bible import would remove referenced verses: ${catalogBook.code} $chapterNumber $removedNumbers"
        }

        chapter.mark(
            BibleChapterContentStatus.AVAILABLE,
            source.verses.size,
            source.sourcePath,
            source.checksum,
            Instant.now(clock),
        )
        chapterRepository.saveAndFlush(chapter)

        if (postgres) {
            batchUpsertVerses(book.id, chapterNumber, source.verses)
        } else {
            val synchronizedVerses = source.verses.map { parsed ->
                existingByNumber[parsed.verse]?.apply { updateText(parsed.text) }
                    ?: BibleVerseEntity(
                        version = BibleCatalog.KOR1910_VERSION,
                        book = book,
                        chapter = chapterNumber,
                        verse = parsed.verse,
                        text = parsed.text,
                    )
            }
            verseRepository.saveAll(synchronizedVerses)
            verseRepository.flush()
        }
        return ChapterImportResult(BibleChapterContentStatus.AVAILABLE, source.verses.size, true)
    }

    private fun batchUpsertVerses(bookId: Long, chapter: Int, verses: List<ParsedBibleVerse>) {
        jdbcTemplate.batchUpdate(UPSERT_VERSE_SQL, verses, VERSE_BATCH_SIZE) { statement, verse ->
            statement.setString(1, BibleCatalog.KOR1910_VERSION)
            statement.setLong(2, bookId)
            statement.setInt(3, chapter)
            statement.setInt(4, verse.verse)
            statement.setString(5, verse.text)
        }
    }

    private fun isPostgresDatabase(): Boolean =
        jdbcTemplate.execute(ConnectionCallback { connection -> connection.metaData.databaseProductName })
            .orEmpty()
            .contains("PostgreSQL", ignoreCase = true)

    private fun acquireImportLock(postgres: Boolean): Boolean {
        if (!postgres) return true
        return jdbcTemplate.queryForObject(
            "select pg_try_advisory_xact_lock(?)",
            Boolean::class.java,
            IMPORT_LOCK_KEY,
        ) ?: false
    }

    private data class ChapterImportResult(
        val status: BibleChapterContentStatus,
        val verseCount: Int,
        val changed: Boolean,
    )

    private data class ChapterKey(val bookId: Long, val chapter: Int)

    companion object {
        private const val IMPORT_LOCK_KEY = 1_910_066L
        private const val VERSE_BATCH_SIZE = 500
        private const val UPSERT_VERSE_SQL = """
            insert into bible_verses(version, book_id, chapter, verse, text)
            values (?, ?, ?, ?, ?)
            on conflict(version, book_id, chapter, verse)
            do update set text = excluded.text
        """
    }
}

/** 운영 로그와 테스트에서 사용하는 전체 성경 import 집계 결과입니다. */
data class BibleImportSummary(
    val lockAcquired: Boolean = true,
    val books: Int = 0,
    val chapters: Int = 0,
    val availableChapters: Int = 0,
    val emptyChapters: Int = 0,
    val sourceMissingChapters: Int = 0,
    val verses: Int = 0,
    val changedChapters: Int = 0,
)
