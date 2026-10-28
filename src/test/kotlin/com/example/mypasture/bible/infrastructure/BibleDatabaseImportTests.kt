package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.domain.BibleChapterContentStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

/** 샘플 원본의 권·장·절 DB 적재, 빈 장 표현과 반복 실행 멱등성을 검증합니다. */
@SpringBootTest
@TestPropertySource(properties = ["app.bible.kor1910-html-path=src/test/resources/bible/kor_html", "app.bible.import-enabled=true"])
class BibleDatabaseImportTests(
    @Autowired private val coordinator: BibleImportCoordinator,
    @Autowired private val bookRepository: BibleBookEntityRepository,
    @Autowired private val chapterRepository: BibleChapterEntityRepository,
    @Autowired private val verseRepository: BibleVerseEntityRepository,
) {
    @Test
    fun `정규 성경 책 장 절을 가져오고 없는 장은 빈 결과를 반환한다`() {
        coordinator.importAll()
        val firstPeter = bookRepository.findByVersionAndCode(BibleCatalog.KOR1910_VERSION, "1PE")!!
        val missingChapter = chapterRepository.findByVersionAndBook_IdAndChapter(
            BibleCatalog.KOR1910_VERSION,
            firstPeter.id,
            5,
        )!!

        assertEquals(66, bookRepository.count())
        assertEquals(1_189, chapterRepository.count())
        assertEquals(4, verseRepository.count())
        assertEquals(BibleChapterContentStatus.EMPTY, missingChapter.contentStatus)
        assertEquals(0, missingChapter.verseCount)
    }

    @Test
    fun `두 번째 가져오기에서도 절 ID를 유지하고 중복 장을 만들지 않는다`() {
        coordinator.importAll()
        val beforeIds = verseRepository.findAll().map { it.id }.toSet()

        val summary = coordinator.importAll()

        assertTrue(summary.lockAcquired)
        assertEquals(0, summary.changedChapters)
        assertEquals(1_189, chapterRepository.count())
        assertEquals(beforeIds, verseRepository.findAll().map { it.id }.toSet())
    }
}
