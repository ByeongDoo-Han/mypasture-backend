package com.example.mypasture.daily_word.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 모든 사용자에게 같은 날 동일하게 제공되는 오늘의 말씀과 묵상 콘텐츠입니다. */
@Entity
@Table(name = "daily_words")
class DailyWordEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(unique = true) val wordDate: LocalDate,
    val bibleVersion: String,
    val bookCode: String,
    val bookName: String,
    val chapter: Int,
    val verse: Int,
    @Column(columnDefinition = "text") val verseText: String,
    @Column(columnDefinition = "text") val meditation: String,
    @Column(columnDefinition = "text") val actionQuestion: String,
    val published: Boolean = true,
    val createdAt: Instant = Instant.now(),
)
