package com.example.mypasture.quiet_time.application

import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.quiet_time.domain.QuietTimePassageEntity
import com.example.mypasture.quiet_time.domain.QuietTimeQuestionStyle
import com.example.mypasture.quiet_time.infrastructure.QuietTimePassageRepository
import com.example.mypasture.quiet_time.infrastructure.QuietTimeSharedQuestionStore
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** 같은 번역본·절 범위·스타일의 AI QT 질문을 목장 간 재사용합니다. */
@Service
class QuietTimeSharedQuestionService(
    private val passages: QuietTimePassageRepository,
    private val bibleReader: BibleReaderService,
    private val generator: QuietTimeQuestionGenerationPort,
    private val store: QuietTimeSharedQuestionStore,
) {
    fun status(passageId: UUID, style: QuietTimeQuestionStyle): QuietTimeGenerationView {
        val source = source(passageId)
        val stored = store.state(passageId, style, VERSION, source.hash)
        return QuietTimeGenerationView(stored?.status ?: "NOT_STARTED", stored?.questionSet)
    }

    suspend fun generate(passageId: UUID, style: QuietTimeQuestionStyle, userId: UUID): QuietTimeGenerationView {
        val source = source(passageId)
        store.state(passageId, style, VERSION, source.hash)?.takeIf { it.status == "READY" }?.let {
            return QuietTimeGenerationView("READY", it.questionSet)
        }
        val token = store.claim(passageId, style, VERSION, source.hash, userId, Instant.now())
            ?: return status(passageId, style)
        return try {
            val draft = generator.generate(source.reference, source.selectedText, source.contextText, style).validate()
            val result = QuietTimeQuestionSet(
                focusTheme = QuietTimePassageThemeView("GENERAL", "말씀 묵상", null, "선택한 성경 본문을 함께 묵상합니다.", 100, true, source.reference),
                style = style, version = VERSION,
                observationQuestions = draft.observationQuestions,
                meditationQuestions = draft.meditationQuestions,
                sharingQuestions = draft.sharingQuestions,
                actionGuide = draft.actionGuide,
                prayerGuide = draft.prayerGuide,
            )
            if (store.complete(passageId, style, VERSION, source.hash, token, result, draft)) {
                QuietTimeGenerationView("READY", result)
            } else status(passageId, style)
        } catch (error: Exception) {
            store.fail(passageId, style, VERSION, source.hash, token)
            throw error
        }
    }

    fun readyForSession(passage: QuietTimePassageEntity, style: QuietTimeQuestionStyle): QuietTimeQuestionSet {
        val result = status(passage.id, style)
        return result.questionSet?.takeIf { result.status == "READY" }
            ?: throw QuietTimeQuestionNotReadyException()
    }

    private fun source(passageId: UUID): PassageSource {
        val passage = passages.findByIdAndActiveTrue(passageId)
            ?: throw QuietTimeNotFoundException("Active quiet time passage was not found: $passageId")
        require(passage.origin == "USER_SELECTED") { "직접 선택한 QT 본문만 AI 질문을 생성할 수 있습니다." }
        val chapter = bibleReader.getChapter(passage.bibleVersion, passage.bookCode, passage.startChapter)
        val selected = chapter.verses.filter { it.verse in passage.startVerse..passage.endVerse }
        require(selected.size == passage.endVerse - passage.startVerse + 1) { "선택한 말씀 원문을 확인할 수 없습니다." }
        val context = chapter.verses.filter { it.verse in (passage.startVerse - 2)..(passage.endVerse + 2) }
        val selectedText = selected.joinToString("\n") { "${it.verse}. ${it.text}" }.take(5_000)
        val contextText = context.joinToString("\n") { "${it.verse}. ${it.text}" }.take(7_000)
        val hashInput = "${passage.bibleVersion}:${passage.bookCode}:${passage.startChapter}:${passage.startVerse}:${passage.endVerse}:$contextText"
        val hash = MessageDigest.getInstance("SHA-256").digest(hashInput.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return PassageSource(passage.title, selectedText, contextText, hash)
    }

    private data class PassageSource(val reference: String, val selectedText: String, val contextText: String, val hash: String)

    companion object { const val VERSION = 2 }
}

/** 생성 상태를 조회하거나 완료된 공용 질문을 반환합니다. */
data class QuietTimeGenerationView(val status: String, val questionSet: QuietTimeQuestionSet?)

/** QT 세션 생성 시 선택한 본문의 질문 생성이 완료되지 않았음을 알립니다. */
class QuietTimeQuestionNotReadyException : RuntimeException("질문 생성이 완료된 뒤 모임을 만들어 주세요.")
