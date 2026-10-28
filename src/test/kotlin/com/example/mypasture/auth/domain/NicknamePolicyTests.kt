package com.example.mypasture.auth.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 이메일과 소셜 닉네임의 공통 경계 조건을 검증합니다. */
class NicknamePolicyTests {
    @Test
    fun `한글과 영어 닉네임 길이 제한 및 혼합 이름을 검증한다`() {
        assertTrue(NicknamePolicy.isValid("가".repeat(8)))
        assertFalse(NicknamePolicy.isValid("가".repeat(9)))
        assertTrue(NicknamePolicy.isValid("Abcdefghij"))
        assertFalse(NicknamePolicy.isValid("Abcdefghijk"))
        assertTrue(NicknamePolicy.isValid("가".repeat(4) + "a".repeat(5)))
        assertFalse(NicknamePolicy.isValid("가".repeat(4) + "a".repeat(6)))
    }

    @Test
    fun `띄어쓰기 문장부호 이모지 숫자 자모를 거부한다`() {
        listOf("", "새 이름", "한_글", "사랑🙂", "Test1", "ㄱㄴ").forEach {
            assertFalse(NicknamePolicy.isValid(it))
        }
    }

    @Test
    fun `소셜 닉네임 후보는 한글 5자 또는 6자다`() {
        val candidates = SocialNicknameGenerator.candidates()
        assertTrue(candidates.size > 300)
        assertTrue(candidates.distinct().size == candidates.size)
        assertTrue(candidates.all { it.length in 5..6 && NicknamePolicy.isValid(it) })
    }
}
