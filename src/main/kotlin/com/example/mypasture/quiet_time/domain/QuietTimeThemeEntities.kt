package com.example.mypasture.quiet_time.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.Id
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.Instant
import java.util.UUID

/** QT 본문 분류와 리더의 초점 선택에 사용하는 검수 가능한 주제 사전입니다. */
@Entity
@Table(name = "quiet_time_themes")
class QuietTimeThemeEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @Column(unique = true, length = 50)
    val code: String,
    @Column(length = 80)
    val name: String,
    @Column(length = 50)
    val parentCode: String? = null,
    @Column(columnDefinition = "text")
    val description: String,
    val active: Boolean = true,
    val createdAt: Instant = Instant.now(),
)

/** 하나의 성경 본문에 연결된 복수 주제의 중요도와 질문 작성 근거를 저장합니다. */
@Entity
@Table(
    name = "quiet_time_passage_themes",
    uniqueConstraints = [UniqueConstraint(name = "uk_quiet_time_passage_theme", columnNames = ["passage_id", "theme_id"])],
)
class QuietTimePassageThemeEntity(
    @Id
    val id: UUID = UUID.randomUUID(),
    @ManyToOne(fetch = FetchType.LAZY)
    val passage: QuietTimePassageEntity,
    @ManyToOne(fetch = FetchType.LAZY)
    val theme: QuietTimeThemeEntity,
    val relevanceScore: Int,
    val primaryTheme: Boolean,
    @Column(length = 120)
    val evidenceReference: String,
    @Column(columnDefinition = "text")
    val reflectionPrompt: String,
    @Column(columnDefinition = "text")
    val sharingPrompt: String,
    @Column(columnDefinition = "text")
    val actionPrompt: String,
    val createdAt: Instant = Instant.now(),
)
