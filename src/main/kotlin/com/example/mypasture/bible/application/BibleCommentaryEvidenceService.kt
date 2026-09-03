package com.example.mypasture.bible.application

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceEntity
import com.example.mypasture.bible.domain.BibleCommentaryEvidenceStatus
import com.example.mypasture.bible.infrastructure.BibleCommentaryEvidenceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.net.URI
import java.util.UUID

/** 장별 해설에 제공할 출처·사용권 확인 메모의 등록, 승인과 폐기 정책을 담당합니다. */
@Service
@Transactional(readOnly = true)
class BibleCommentaryEvidenceService(
    private val repository: BibleCommentaryEvidenceRepository,
    private val bibleReaderService: BibleReaderService,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(version: String, bookCode: String, chapter: Int): List<BibleCommentaryEvidence> {
        bibleReaderService.getChapter(version, bookCode, chapter)
        return repository
            .findAllByVersionAndBookCodeAndChapterIsNullOrVersionAndBookCodeAndChapterOrderByCreatedAtDesc(
                version.uppercase(),
                bookCode.uppercase(),
                version.uppercase(),
                bookCode.uppercase(),
                chapter,
            ).map { it.toModel() }
    }

    fun approvedFor(version: String, bookCode: String, chapter: Int): List<BibleCommentaryEvidence> =
        repository.findApplicable(
            version.uppercase(),
            bookCode.uppercase(),
            chapter,
            BibleCommentaryEvidenceStatus.APPROVED,
        ).map { it.toModel() }
            .sortedBy { if (it.chapter == chapter) 0 else 1 }
            .take(MAX_EVIDENCE_PER_GENERATION)

    @Transactional
    fun create(command: CreateBibleCommentaryEvidenceCommand, adminId: UUID): BibleCommentaryEvidence {
        val normalizedVersion = command.version.trim().uppercase()
        val book = BibleCatalog.findBook(command.bookCode)
            ?: throw BibleNotFoundException("Bible book was not found: ${command.bookCode}")
        bibleReaderService.getBooks(normalizedVersion)
        command.chapter?.let {
            require(it in 1..book.chapterCount) { "근거 메모의 장 번호가 올바르지 않습니다." }
        }
        val title = command.title.trim()
        val content = command.content.trim()
        val sourceName = command.sourceName.trim()
        val licenseNote = command.licenseNote.trim()
        require(title.length in 2..200) { "근거 제목은 2자에서 200자 사이여야 합니다." }
        require(content.length in 20..4000) { "근거 내용은 20자에서 4000자 사이여야 합니다." }
        require(sourceName.length in 2..240) { "출처명은 2자에서 240자 사이여야 합니다." }
        require(licenseNote.length in 2..500) { "사용권 메모는 2자에서 500자 사이여야 합니다." }
        val sourceUrl = command.sourceUrl?.trim()?.takeIf { it.isNotEmpty() }
        require(sourceUrl == null || sourceUrl.length <= 1000) { "출처 URL은 1000자를 넘을 수 없습니다." }
        sourceUrl?.let {
            val uri = runCatching { URI(it) }.getOrElse { throw IllegalArgumentException("출처 URL 형식이 올바르지 않습니다.") }
            require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
                "출처 URL은 호스트가 있는 HTTPS 주소만 허용됩니다."
            }
        }

        return repository.save(
            BibleCommentaryEvidenceEntity(
                version = normalizedVersion,
                bookCode = book.code,
                chapter = command.chapter,
                evidenceType = command.evidenceType,
                title = title,
                content = content,
                sourceName = sourceName,
                sourceUrl = sourceUrl,
                licenseNote = licenseNote,
                createdBy = adminId,
                createdAt = Instant.now(clock),
                updatedAt = Instant.now(clock),
            ),
        ).toModel()
    }

    @Transactional
    fun approve(evidenceId: UUID, adminId: UUID): BibleCommentaryEvidence {
        val entity = repository.findById(evidenceId).orElseThrow {
            BibleCommentaryNotFoundException("해설 근거", 0)
        }
        entity.approve(adminId, Instant.now(clock))
        return entity.toModel()
    }

    @Transactional
    fun retire(evidenceId: UUID): BibleCommentaryEvidence {
        val entity = repository.findById(evidenceId).orElseThrow {
            BibleCommentaryNotFoundException("해설 근거", 0)
        }
        entity.retire(Instant.now(clock))
        return entity.toModel()
    }

    private companion object {
        const val MAX_EVIDENCE_PER_GENERATION = 8
    }
}

private fun BibleCommentaryEvidenceEntity.toModel(): BibleCommentaryEvidence =
    BibleCommentaryEvidence(
        id = id,
        version = version,
        bookCode = bookCode,
        chapter = chapter,
        evidenceType = evidenceType,
        title = title,
        content = content,
        sourceName = sourceName,
        sourceUrl = sourceUrl,
        licenseNote = licenseNote,
        status = status,
        createdBy = createdBy,
        approvedBy = approvedBy,
        approvedAt = approvedAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
