package com.example.mypasture.bible.application

import com.example.mypasture.achievement.application.AchievementService
import com.example.mypasture.bible.domain.BibleNoteEntity
import com.example.mypasture.bible.domain.BibleNoteVerseEntity
import com.example.mypasture.bible.domain.BibleVerseEntity
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleNoteRepository
import com.example.mypasture.bible.infrastructure.BibleNoteVerseRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

data class BibleNoteVerseSelection(
    val version: String,
    val bookCode: String,
    val chapter: Int,
    val verse: Int,
)

data class BibleNoteDetail(
    val note: BibleNoteEntity,
    val verseLinks: List<BibleNoteVerseEntity>,
)

data class BibleNotePage(
    val items: List<BibleNoteDetail>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
)

class BibleNoteNotFoundException : RuntimeException("Bible note was not found.")

class BibleNoteConflictException : RuntimeException("Bible note was changed by another request.")

/** 메모 검증, 성경 절 확인, 사용자 소유권과 낙관적 잠금을 관리합니다. */
@Service
class BibleNoteService(
    private val bookRepository: BibleBookEntityRepository,
    private val verseRepository: BibleVerseEntityRepository,
    private val noteRepository: BibleNoteRepository,
    private val noteVerseRepository: BibleNoteVerseRepository,
    private val achievements: AchievementService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun create(userId: UUID, content: String, selections: List<BibleNoteVerseSelection>): BibleNoteDetail {
        val normalizedContent = validateContent(content)
        val verses = resolveVerses(selections)
        achievements.lockUser(userId)
        val now = Instant.now(clock)
        val note = noteRepository.save(BibleNoteEntity(userId = userId, content = normalizedContent, createdAt = now, updatedAt = now))
        val links = saveLinks(note, verses)
        achievements.preserveNotesAndRecord(userId, now.atZone(ZoneId.of("Asia/Seoul")).toLocalDate())
        return BibleNoteDetail(note, links)
    }

    @Transactional(readOnly = true)
    fun findAll(userId: UUID, page: Int, size: Int): BibleNotePage {
        require(page >= 0) { "Page must be greater than or equal to 0." }
        require(size in 1..100) { "Size must be between 1 and 100." }
        val sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))
        val notes = noteRepository.findAllByUserId(userId, PageRequest.of(page, size, sort))
        val linksByNote = if (notes.isEmpty) {
            emptyMap()
        } else {
            noteVerseRepository.findAllByNoteIds(notes.content.map { it.id }).groupBy { it.note.id }
        }
        return BibleNotePage(
            items = notes.content.map { note -> BibleNoteDetail(note, linksByNote[note.id].orEmpty()) },
            page = notes.number,
            size = notes.size,
            totalElements = notes.totalElements,
            totalPages = notes.totalPages,
        )
    }

    @Transactional
    fun update(
        userId: UUID,
        noteId: UUID,
        expectedRevision: Long,
        content: String,
        selections: List<BibleNoteVerseSelection>,
    ): BibleNoteDetail {
        require(expectedRevision >= 0) { "Expected revision must be greater than or equal to 0." }
        achievements.lockUser(userId)
        val note = ownedNote(userId, noteId)
        if (note.revision != expectedRevision) throw BibleNoteConflictException()
        val normalizedContent = validateContent(content)
        val verses = resolveVerses(selections)
        note.revise(normalizedContent)
        noteVerseRepository.deleteAllByNoteId(note.id)
        // 삭제와 note revision 증가를 먼저 flush해 동시 수정 충돌 시 새 연결 저장 전 중단합니다.
        noteVerseRepository.flush()
        return BibleNoteDetail(note, saveLinks(note, verses))
    }

    @Transactional
    fun delete(userId: UUID, noteId: UUID) {
        achievements.lockUser(userId)
        val note = ownedNote(userId, noteId)
        achievements.preserveNotesAndRecord(userId)
        noteVerseRepository.deleteAllByNoteId(note.id)
        noteRepository.delete(note)
    }

    private fun ownedNote(userId: UUID, noteId: UUID) =
        noteRepository.findByIdAndUserId(noteId, userId) ?: throw BibleNoteNotFoundException()

    private fun validateContent(content: String): String {
        val normalized = content.trim()
        require(normalized.length in 1..2000) { "Memo must be between 1 and 2000 characters." }
        return normalized
    }

    private fun resolveVerses(selections: List<BibleNoteVerseSelection>): List<BibleVerseEntity> {
        require(selections.size in 1..50) { "Between 1 and 50 verses must be selected." }
        val keys = selections.map(::normalizeSelection)
        require(keys.distinct().size == keys.size) { "Duplicate verses cannot be selected." }

        val resolved = mutableMapOf<VerseKey, BibleVerseEntity>()
        keys.groupBy { VerseGroup(it.version, it.bookCode, it.chapter) }.forEach { (group, requested) ->
            val book = bookRepository.findByVersionAndCode(group.version, group.bookCode)
                ?: throw BibleNotFoundException("Bible book was not found: ${group.bookCode}")
            val versesByNumber = verseRepository
                .findAllByVersionAndBook_IdAndChapterOrderByVerse(group.version, book.id, group.chapter)
                .associateBy { it.verse }
            requested.forEach { key ->
                resolved[key] = versesByNumber[key.verse]
                    ?: throw BibleNotFoundException("Bible verse was not found: ${key.bookCode} ${key.chapter}:${key.verse}")
            }
        }
        return keys.map(resolved::getValue)
    }

    private fun normalizeSelection(selection: BibleNoteVerseSelection): VerseKey {
        val version = selection.version.trim().uppercase()
        val bookCode = selection.bookCode.trim().uppercase()
        require(version.length in 1..40) { "Bible version must be between 1 and 40 characters." }
        require(bookCode.length in 1..40) { "Book code must be between 1 and 40 characters." }
        require(selection.chapter >= 1 && selection.verse >= 1) { "Chapter and verse must be positive." }
        return VerseKey(version, bookCode, selection.chapter, selection.verse)
    }

    private fun saveLinks(note: BibleNoteEntity, verses: List<BibleVerseEntity>) =
        noteVerseRepository.saveAll(
            verses.mapIndexed { index, verse -> BibleNoteVerseEntity(note = note, verse = verse, sortOrder = index) },
        )

    private data class VerseGroup(val version: String, val bookCode: String, val chapter: Int)

    private data class VerseKey(val version: String, val bookCode: String, val chapter: Int, val verse: Int)
}
