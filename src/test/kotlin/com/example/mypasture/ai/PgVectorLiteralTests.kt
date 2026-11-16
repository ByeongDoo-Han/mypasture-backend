package com.example.mypasture.ai

import com.example.mypasture.ai.infrastructure.PgVectorLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** JDBC parameter로 전달하는 pgvector literal의 형식과 비정상 값 차단을 검증합니다. */
class PgVectorLiteralTests {
    @Test
    fun `유한한 실수 벡터를 직렬화한다`() {
        assertEquals("[0.25,-0.5,1.0]", PgVectorLiteral.from(floatArrayOf(0.25f, -0.5f, 1f)))
    }

    @Test
    fun `유한하지 않은 값을 거부한다`() {
        assertThrows(IllegalArgumentException::class.java) {
            PgVectorLiteral.from(floatArrayOf(Float.NaN))
        }
    }
}
