package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleCatalog
import com.example.mypasture.bible.application.BibleReaderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource

/** 개역한글판 DB import의 버전 격리와 반복 실행 멱등성을 검증합니다. */
@SpringBootTest
@TestPropertySource(properties = ["app.bible.import-enabled=false", "app.bible.krv-import-enabled=false"])
class KrvBibleImportTests(
    @Autowired private val coordinator: BibleImportCoordinator,
    @Autowired private val readerService: BibleReaderService,
    @Autowired private val bookRepository: BibleBookEntityRepository,
    @Autowired private val chapterRepository: BibleChapterEntityRepository,
    @Autowired private val verseRepository: BibleVerseEntityRepository,
) {
    @Test
    fun `개역한글 전체 절을 한 번 가져오고 절 ID를 보존한다`() {
        assertEquals(false, readerService.getVersions().any { it.code == BibleCatalog.KRV_VERSION })
        val first = coordinator.importKrv()
        assertEquals(66, first.books)
        assertEquals(1_189, first.chapters)
        assertEquals(31_101, first.verses)
        assertEquals(true, readerService.getVersions().any { it.code == BibleCatalog.KRV_VERSION })
        val peter = bookRepository.findByVersionAndCode(BibleCatalog.KRV_VERSION, "1PE")!!
        assertEquals(14, chapterRepository.findByVersionAndBook_IdAndChapter(BibleCatalog.KRV_VERSION, peter.id, 5)?.verseCount)
        assertNotNull(verseRepository.findByVersionAndBook_IdAndChapterAndVerse(BibleCatalog.KRV_VERSION, peter.id, 5, 14))
        assertEquals(null, bookRepository.findByVersionAndCode(BibleCatalog.KOR1910_VERSION, "1PE"))
        val before = verseRepository.findAllByVersionAndBook_IdAndChapterOrderByVerse(BibleCatalog.KRV_VERSION, peter.id, 5).map { it.id }
        val second = coordinator.importKrv()
        assertEquals(0, second.changedChapters)
        assertEquals(before, verseRepository.findAllByVersionAndBook_IdAndChapterOrderByVerse(BibleCatalog.KRV_VERSION, peter.id, 5).map { it.id })
    }
}
