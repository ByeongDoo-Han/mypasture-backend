package com.example.mypasture.bible.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant

/** DB에 저장된 성경 번역본별 권 메타데이터를 표현합니다. */
@Entity
@Table(name = "bible_books", uniqueConstraints = [UniqueConstraint(columnNames = ["version", "code"])])
class BibleBookEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    var version: String,
    var code: String,
    var name: String,
    @Enumerated(EnumType.STRING) var testament: Testament,
    var displayOrder: Int,
) {
    fun synchronize(book: BibleBook) {
        name = book.name
        testament = book.testament
        displayOrder = book.displayOrder
    }
}

/** 원문 존재 여부와 import 결과를 장 단위로 기록합니다. */
@Entity
@Table(
    name = "bible_chapters",
    uniqueConstraints = [UniqueConstraint(columnNames = ["version", "book_id", "chapter"])],
)
class BibleChapterEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    var version: String,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "book_id") val book: BibleBookEntity,
    val chapter: Int,
    @Enumerated(EnumType.STRING) var contentStatus: BibleChapterContentStatus,
    var verseCount: Int = 0,
    var sourcePath: String? = null,
    @Column(length = 64) var sourceChecksum: String? = null,
    var importedAt: Instant = Instant.now(),
) {
    fun mark(
        status: BibleChapterContentStatus,
        count: Int,
        path: String?,
        checksum: String?,
        now: Instant,
    ) {
        contentStatus = status
        verseCount = count
        sourcePath = path
        sourceChecksum = checksum
        importedAt = now
    }
}

/** 절 단위 원문을 저장하며 북마크·하이라이트·말씀 카드가 참조할 안정적인 ID를 제공합니다. */
@Entity
@Table(
    name = "bible_verses",
    uniqueConstraints = [UniqueConstraint(columnNames = ["version", "book_id", "chapter", "verse"])],
)
class BibleVerseEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long = 0,
    val version: String,
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "book_id") val book: BibleBookEntity,
    val chapter: Int,
    val verse: Int,
    @Column(columnDefinition = "text") var text: String,
) {
    fun updateText(value: String) {
        text = value
    }
}
