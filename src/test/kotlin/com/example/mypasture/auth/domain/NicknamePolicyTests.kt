package com.example.mypasture.auth.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 이메일과 소셜 닉네임의 공통 경계 조건을 검증합니다. */
class NicknamePolicyTests {
    @Test
    fun `Korean and English limits and mixed names`() {
        assertTrue(NicknamePolicy.isValid("가".repeat(8)))
        assertFalse(NicknamePolicy.isValid("가".repeat(9)))
        assertTrue(NicknamePolicy.isValid("Abcdefghij"))
        assertFalse(NicknamePolicy.isValid("Abcdefghijk"))
        assertTrue(NicknamePolicy.isValid("가".repeat(4) + "a".repeat(5)))
        assertFalse(NicknamePolicy.isValid("가".repeat(4) + "a".repeat(6)))
    }

    @Test
    fun `spaces punctuation emoji digits and jamo are rejected`() {
        listOf("", "새 이름", "한_글", "사랑🙂", "Test1", "ㄱㄴ").forEach {
            assertFalse(NicknamePolicy.isValid(it))
        }
    }

    @Test
    fun `social nickname candidates are Korean and five or six characters`() {
        val candidates = SocialNicknameGenerator.candidates()
        assertTrue(candidates.size > 300)
        assertTrue(candidates.distinct().size == candidates.size)
        assertTrue(candidates.all { it.length in 5..6 && NicknamePolicy.isValid(it) })
    }
}
