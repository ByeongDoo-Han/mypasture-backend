package com.example.mypasture.prayer.presentation

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping

/** AdMob에서 서명된 보상 완료 콜백을 받는 공개 API 계약입니다. */
@Tag(name = "기도 편지 광고 검증")
interface PrayerRewardCallbackApi {
    @Operation(summary = "AdMob 기도 편지 광고 보상 검증 콜백", description = "Google이 서명한 광고 완료 콜백을 검증해 주간 편지 발송권을 한 명 추가합니다.")
    @GetMapping("/ssv")
    fun callback(request: HttpServletRequest)
}
