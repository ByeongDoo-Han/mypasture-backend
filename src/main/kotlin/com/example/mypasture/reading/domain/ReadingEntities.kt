package com.example.mypasture.reading.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 사용자가 특정 성경 장에서 마지막으로 읽은 절과 완독 여부를 저장합니다. */
@Entity
@Table(name = "bible_reading_states", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "bible_version", "book_code", "chapter"])])
class BibleReadingStateEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id") val userId: UUID,
    @Column(name = "bible_version") val bibleVersion: String,
    @Column(name = "book_code") val bookCode: String,
    val chapter: Int,
    var lastVerse: Int,
    var completed: Boolean,
    var lastReadAt: Instant = Instant.now(),
) {
    fun record(lastVerse: Int, completed: Boolean, now: Instant) {
        this.lastVerse = maxOf(this.lastVerse, lastVerse)
        this.completed = this.completed || completed
        this.lastReadAt = now
    }
}

/** 날짜별 성경 읽기 활동을 한 장당 한 행으로 집계합니다. */
@Entity
@Table(name = "bible_reading_history", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "bible_version", "book_code", "chapter", "read_on"])])
class BibleReadingHistoryEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id") val userId: UUID,
    @Column(name = "bible_version") val bibleVersion: String,
    @Column(name = "book_code") val bookCode: String,
    val chapter: Int,
    val readOn: LocalDate,
    var lastVerse: Int,
    var completed: Boolean,
    var updatedAt: Instant = Instant.now(),
) {
    fun record(lastVerse: Int, completed: Boolean, now: Instant) {
        this.lastVerse = maxOf(this.lastVerse, lastVerse)
        this.completed = this.completed || completed
        this.updatedAt = now
    }
}
