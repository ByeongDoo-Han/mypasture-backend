package com.example.mypasture.bible.infrastructure

import com.example.mypasture.bible.domain.BibleBook
import com.example.mypasture.bible.domain.BibleCatalog
import com.fasterxml.jackson.databind.ObjectMapper
import java.security.MessageDigest
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component

/** PDF에서 검증해 포함한 개역한글판 JSON을 장 단위 import 원본으로 제공합니다. */
@Component
class KrvBibleSource(private val objectMapper: ObjectMapper) {
    private val root by lazy {
        ClassPathResource("bible/krv.json").inputStream.use { objectMapper.readTree(it) }
    }

    fun validateSource() {
        require(root.path("version").asText() == BibleCatalog.KRV_VERSION) { "Invalid KRV source version" }
        val books = root.path("books")
        require(books.size() == BibleCatalog.books.size) { "Invalid KRV book count" }
        var count = 0
        BibleCatalog.books.forEach { book ->
            val chapters = books.path(book.code)
            require(chapters.size() == book.chapterCount) { "Invalid KRV chapters: ${book.code}" }
            (1..book.chapterCount).forEach { chapter ->
                val verses = chapters.path(chapter.toString())
                require(verses.isArray && verses.size() > 0) { "Missing KRV verses: ${book.code} $chapter" }
                verses.forEachIndexed { index, verse ->
                    require(verse.isArray && verse.size() == 2 && verse[0].asInt() == index + 1 && verse[1].asText().isNotBlank()) {
                        "Invalid KRV verse: ${book.code} $chapter:${index + 1}"
                    }
                    count++
                }
            }
        }
        require(count == 31_101) { "Invalid KRV verse count: $count" }
    }

    fun readChapter(book: BibleBook, chapter: Int): ParsedBibleChapterSource {
        val value = root.path("books").path(book.code).path(chapter.toString())
        require(value.isArray && value.size() > 0) { "Missing KRV chapter: ${book.code} $chapter" }
        val verses = value.map { ParsedBibleVerse(it[0].asInt(), it[1].asText()) }
        val digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(value))
            .joinToString("") { "%02x".format(it) }
        return ParsedBibleChapterSource("krv.json:${book.code}:$chapter", digest, verses)
    }
}

/** 명시적으로 활성화할 때만 개역한글판을 영속 DB에 동기화합니다. */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = ["app.bible.krv-import-enabled"], havingValue = "true")
class KrvBibleImporter(private val coordinator: BibleImportCoordinator) : org.springframework.boot.ApplicationRunner {
    override fun run(args: org.springframework.boot.ApplicationArguments) {
        coordinator.importKrv()
    }
}
