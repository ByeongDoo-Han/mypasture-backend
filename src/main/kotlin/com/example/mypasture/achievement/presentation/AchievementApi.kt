package com.example.mypasture.achievement.presentation

import com.example.mypasture.achievement.application.AchievementSummary
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody

/** 본인의 업적 조회·기존 기록 소급·대표 배지 선택 계약입니다. */
interface AchievementApi {
    /** 조회만 하며 새로운 업적을 수여하지 않습니다. */
    @GetMapping
    fun summary(@AuthenticationPrincipal principal: JwtPrincipal): AchievementSummary

    /** 최초 수여 시각을 보존하며 기존 기록을 멱등하게 소급 평가합니다. */
    @PostMapping("/reconcile")
    fun reconcile(@AuthenticationPrincipal principal: JwtPrincipal): AchievementSummary

    /** 본인이 획득한 서로 다른 배지 최대 세 개를 지정한 순서로 저장합니다. */
    @PutMapping("/featured")
    fun featured(@AuthenticationPrincipal principal: JwtPrincipal, @Valid @RequestBody request: FeaturedAchievementsRequest): AchievementSummary
}

/** 비어 있는 목록으로 대표 배지를 모두 해제할 수 있습니다. */
data class FeaturedAchievementsRequest(@field:Size(max = 3) val codes: List<String>)
