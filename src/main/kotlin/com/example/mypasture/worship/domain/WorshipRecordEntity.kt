package com.example.mypasture.worship.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** 예배에서 들은 내용과 개인 응답을 한 성경 장의 연속 말씀에 연결해 보관합니다. */
@Entity
@Table(name = "worship_records")
class WorshipRecordEntity(
    @Id val id: UUID = UUID.randomUUID(),
    @Column(name = "user_id", nullable = false) val userId: UUID,
    @Column(name = "worship_date", nullable = false) var worshipDate: LocalDate,
    @Enumerated(EnumType.STRING)
    @Column(name = "service_type", nullable = false, length = 20) var serviceType: WorshipServiceType,
    @Column(length = 120) var title: String? = null,
    @Column(name = "bible_version", nullable = false, length = 40) var bibleVersion: String,
    @Column(name = "book_code", nullable = false, length = 40) var bookCode: String,
    @Column(name = "book_name", nullable = false, length = 120) var bookName: String,
    @Column(nullable = false) var chapter: Int,
    @Column(name = "start_verse", nullable = false) var startVerse: Int,
    @Column(name = "end_verse", nullable = false) var endVerse: Int,
    @Column(columnDefinition = "text", nullable = false) var heard: String,
    @Column(name = "life_connection", columnDefinition = "text") var lifeConnection: String? = null,
    @Column(name = "personal_response", columnDefinition = "text") var personalResponse: String? = null,
    @Column(columnDefinition = "text") var prayer: String? = null,
    @Column(name = "created_at", nullable = false) val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false) var updatedAt: Instant = Instant.now(),
    @Version @Column(nullable = false) var revision: Long = 0,
)

/** 예배 기록 목록에 표시할 예배 종류입니다. */
enum class WorshipServiceType { SUNDAY, MIDWEEK, DAWN, SPECIAL, OTHER }
