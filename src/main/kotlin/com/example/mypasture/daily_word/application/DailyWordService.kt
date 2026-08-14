package com.example.mypasture.daily_word.application

import com.example.mypasture.bible.application.BibleReaderService
import com.example.mypasture.daily_word.domain.DailyWordEntity
import com.example.mypasture.daily_word.infrastructure.DailyWordRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneId

/** 오늘의 말씀을 하루 한 번 생성해 저장하고 모든 사용자에게 같은 콘텐츠를 반환합니다. */
@Service
class DailyWordService(
    private val repository: DailyWordRepository,
    private val bibleReaderService: BibleReaderService,
) {
    @Transactional
    fun getOrCreate(date: LocalDate = LocalDate.now(SEOUL)): DailyWordEntity {
        repository.findByWordDateAndPublishedTrue(date)?.let { return it }
        val reference = references[Math.floorMod(date.toEpochDay(), references.size.toLong()).toInt()]
        val verse = bibleReaderService.getVerse("KOR1910", reference.bookCode, reference.chapter, reference.verse)
        val entity = DailyWordEntity(
            wordDate = date,
            bibleVersion = verse.version,
            bookCode = verse.bookCode,
            bookName = verse.bookName,
            chapter = verse.chapter,
            verse = verse.verse,
            verseText = verse.text,
            meditation = reference.meditation,
            actionQuestion = reference.actionQuestion,
        )
        return try { repository.saveAndFlush(entity) } catch (_: DataIntegrityViolationException) {
            repository.findByWordDateAndPublishedTrue(date) ?: throw IllegalStateException("오늘의 말씀 동시 생성에 실패했습니다.")
        }
    }

    /** 자정 직후 미리 생성해 첫 사용자 요청에서 생성 비용이나 지연이 발생하지 않게 합니다. */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    fun prepareToday() { getOrCreate() }

    companion object {
        private val SEOUL = ZoneId.of("Asia/Seoul")
        private val references = listOf(
            DailyReference("GEN", 1, 1, "하나님이 시작하신 창조의 질서와 선하심을 바라보며 오늘을 새롭게 시작해 보세요.", "오늘 내가 하나님의 선하심을 드러낼 수 있는 작은 시작은 무엇인가요?"),
            DailyReference("PSA", 23, 1, "하나님을 목자로 신뢰한다는 것이 오늘의 선택에 어떤 평안을 주는지 천천히 생각해 보세요.", "오늘 내가 혼자 감당하려 했던 한 가지를 하나님께 맡길 수 있을까요?"),
            DailyReference("PHP", 4, 6, "염려를 없애려 애쓰기보다 구체적인 기도와 감사로 하나님께 가져가 보세요.", "지금 감사와 함께 기도로 아뢸 한 가지는 무엇인가요?"),
            DailyReference("MAT", 6, 33, "먼저 하나님의 나라와 의를 구하는 삶의 우선순위를 돌아보세요.", "오늘 한 가지 선택에서 하나님 나라를 먼저 구한다면 무엇이 달라질까요?"),
        )
    }
}

private data class DailyReference(val bookCode: String, val chapter: Int, val verse: Int, val meditation: String, val actionQuestion: String)
