package com.example.mypasture.bible.application

import com.example.mypasture.auth.application.InvalidCredentialsException
import com.example.mypasture.auth.infrastructure.UserRepository
import com.example.mypasture.bible.domain.ScriptureCopyEntity
import com.example.mypasture.bible.domain.ScriptureCopyStatus
import com.example.mypasture.bible.infrastructure.BibleBookEntityRepository
import com.example.mypasture.bible.infrastructure.BibleVerseEntityRepository
import com.example.mypasture.bible.infrastructure.ScriptureCopyRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.text.Normalizer
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

/** 필사 저장 요청의 입력과 낙관적 수정 조건입니다. */
data class ScriptureCopyCommand(
    val version: String, val bookCode: String, val chapter: Int, val verse: Int,
    val typedText: String, val reflection: String, val expectedRevision: Long, val complete: Boolean,
    val endVerse: Int? = null,
)

/** 본인 필사 기록이 없거나 다른 사용자가 소유한 경우입니다. */
class ScriptureCopyNotFoundException : RuntimeException()
/** 오래된 요청이나 완료 기록 변경으로 저장할 수 없는 경우입니다. */
class ScriptureCopyConflictException : RuntimeException()
/** 원문과 일치하지 않아 필사를 완료할 수 없는 경우입니다. */
class ScriptureCopyIncompleteException : RuntimeException()

/** 사용자 행 잠금으로 재시도·수정·탈퇴를 직렬화하고 서버 원문으로 완료를 검증합니다. */
@Service
class ScriptureCopyService(
    private val users: UserRepository,
    private val copies: ScriptureCopyRepository,
    private val books: BibleBookEntityRepository,
    private val verses: BibleVerseEntityRepository,
) {
    @Transactional
    fun save(userId: UUID, id: UUID, request: ScriptureCopyCommand): ScriptureCopyEntity {
        val command = validate(request)
        lockActiveUser(userId)
        val existing = copies.findById(id).orElse(null)
        if (existing != null) {
            if (existing.userId != userId) throw ScriptureCopyNotFoundException()
            if (existing.version != command.version || existing.bookCode != command.bookCode ||
                existing.chapter != command.chapter || existing.verse != command.verse ||
                existing.endVerse != (command.endVerse ?: existing.endVerse)) {
                throw ScriptureCopyConflictException()
            }
            val nextStatus = if (command.complete) ScriptureCopyStatus.COMPLETED else ScriptureCopyStatus.DRAFT
            // 동일 내용 재시도는 revision이 오래되어도 최초 응답을 그대로 반환합니다.
            if (existing.typedText == command.typedText && existing.reflection == command.reflection && existing.status == nextStatus) return existing
            if (existing.status == ScriptureCopyStatus.COMPLETED || existing.revision != command.expectedRevision) {
                throw ScriptureCopyConflictException()
            }
            if (command.complete) verifyCompletion(existing.sourceText, command.typedText)
            existing.typedText = command.typedText
            existing.reflection = command.reflection
            existing.status = nextStatus
            existing.revision += 1
            existing.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
            if (command.complete) existing.completedAt = existing.updatedAt
            return copies.save(existing)
        }
        if (command.expectedRevision != 0L) throw ScriptureCopyConflictException()
        val book = books.findByVersionAndCode(command.version, command.bookCode)
            ?: throw BibleNotFoundException("Bible book was not found.")
        val endVerse = command.endVerse ?: command.verse
        val selected = if (endVerse == command.verse) {
            listOfNotNull(verses.findByVersionAndBook_IdAndChapterAndVerse(command.version, book.id, command.chapter, command.verse))
        } else {
            verses.findAllByVersionAndBook_IdAndChapterOrderByVerse(command.version, book.id, command.chapter)
                .filter { it.verse in command.verse..endVerse }
        }
        if (selected.size != endVerse - command.verse + 1 || selected.any { it.text.isBlank() }) {
            throw BibleNotFoundException("Bible passage was not found.")
        }
        val sourceText = selected.joinToString("\n") { it.text }
        require(sourceText.length <= 10000) { "Selected passage exceeds the maximum length." }
        if (command.complete) verifyCompletion(sourceText, command.typedText)
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return copies.insert(ScriptureCopyEntity(
            id = id, userId = userId, version = book.version, bookCode = book.code, bookName = book.name,
            chapter = command.chapter, verse = command.verse, endVerse = endVerse, sourceText = sourceText,
            typedText = command.typedText, reflection = command.reflection,
            status = if (command.complete) ScriptureCopyStatus.COMPLETED else ScriptureCopyStatus.DRAFT,
            createdAt = now, updatedAt = now, completedAt = if (command.complete) now else null,
        ))
    }

    /** 완료한 필사 본문은 보존하며 묵상만 별도의 revision 조건으로 수정합니다. */
    @Transactional
    fun updateReflection(userId: UUID, id: UUID, reflection: String, expectedRevision: Long): ScriptureCopyEntity {
        require(reflection.length <= 1000) { "Reflection exceeds the maximum length." }
        require(expectedRevision >= 1) { "Expected revision must be positive." }
        lockActiveUser(userId)
        val record = copies.findByIdAndUserId(id, userId) ?: throw ScriptureCopyNotFoundException()
        if (record.status != ScriptureCopyStatus.COMPLETED) throw ScriptureCopyConflictException()
        if (record.reflection == reflection) return record
        if (record.revision != expectedRevision) throw ScriptureCopyConflictException()
        record.reflection = reflection
        record.revision += 1
        record.updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS)
        return copies.save(record)
    }

    @Transactional(readOnly = true)
    fun get(userId: UUID, id: UUID): ScriptureCopyEntity =
        copies.findByIdAndUserId(id, userId) ?: throw ScriptureCopyNotFoundException()

    @Transactional(readOnly = true)
    fun list(userId: UUID, page: Int, size: Int): Page<ScriptureCopyEntity> {
        require(page >= 0 && size in 1..50) { "Invalid pagination." }
        return copies.findAllByUserId(userId, PageRequest.of(page, size, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))))
    }

    @Transactional
    fun delete(userId: UUID, id: UUID, expectedRevision: Long) {
        require(expectedRevision >= 1) { "Expected revision must be positive." }
        lockActiveUser(userId)
        val record = copies.findById(id).orElse(null) ?: return
        if (record.userId != userId) throw ScriptureCopyNotFoundException()
        if (record.revision != expectedRevision) throw ScriptureCopyConflictException()
        copies.delete(record)
    }

    private fun lockActiveUser(userId: UUID) {
        users.findActiveByIdForUpdate(userId) ?: throw InvalidCredentialsException()
    }

    private fun validate(request: ScriptureCopyCommand): ScriptureCopyCommand {
        val version = request.version.trim().uppercase(Locale.ROOT)
        val bookCode = request.bookCode.trim().uppercase(Locale.ROOT)
        require(version.length in 1..40 && bookCode.length in 1..40) { "Invalid Bible location." }
        require(request.chapter > 0 && request.verse > 0) { "Chapter and verse must be positive." }
        request.endVerse?.let { endVerse ->
            require(endVerse >= request.verse && endVerse - request.verse < 20) { "Select between 1 and 20 consecutive verses." }
        }
        require(request.typedText.length <= 10000 && request.reflection.length <= 1000) { "Text exceeds the maximum length." }
        require(request.expectedRevision >= 0) { "Expected revision cannot be negative." }
        return request.copy(version = version, bookCode = bookCode)
    }

    private fun verifyCompletion(source: String, typed: String) {
        val canonical = comparable(source)
        if (canonical.isEmpty() || canonical != comparable(typed)) throw ScriptureCopyIncompleteException()
    }

    private fun comparable(value: String): String = buildString {
        Normalizer.normalize(value, Normalizer.Form.NFC).codePoints().forEach { codePoint ->
            if (Character.isLetterOrDigit(codePoint) || Character.getType(codePoint) in setOf(
                    Character.NON_SPACING_MARK.toInt(), Character.COMBINING_SPACING_MARK.toInt(), Character.ENCLOSING_MARK.toInt(),
                    Character.LETTER_NUMBER.toInt(), Character.OTHER_NUMBER.toInt(),
                )) appendCodePoint(codePoint)
        }
    }
}
