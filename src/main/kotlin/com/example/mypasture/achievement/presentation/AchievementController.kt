package com.example.mypasture.achievement.presentation

import com.example.mypasture.achievement.application.AchievementService
import com.example.mypasture.auth.infrastructure.JwtPrincipal
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 사용자 식별자를 요청 본문 대신 검증된 JWT에서 추출합니다. */
@RestController
@RequestMapping("/api/v1/achievements")
class AchievementController(private val service: AchievementService) : AchievementApi {
    override fun summary(principal: JwtPrincipal) = service.summary(principal.userId)
    override fun reconcile(principal: JwtPrincipal) = service.reconcile(principal.userId)
    override fun featured(principal: JwtPrincipal, request: FeaturedAchievementsRequest) = service.setFeatured(principal.userId, request.codes)
}
