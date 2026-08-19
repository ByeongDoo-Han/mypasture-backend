package com.example.mypasture.ai

import com.example.mypasture.ai.infrastructure.PgVectorLiteral
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** JDBC parameter로 전달하는 pgvector literal의 형식과 비정상 값 차단을 검증합니다. */
class PgVectorLiteralTests {
    @Test
    fun `serializes finite float vector`() {
        assertEquals("[0.25,-0.5,1.0]", PgVectorLiteral.from(floatArrayOf(0.25f, -0.5f, 1f)))
    }

    @Test
    fun `rejects non finite values`() {
        assertThrows(IllegalArgumentException::class.java) {
            PgVectorLiteral.from(floatArrayOf(Float.NaN))
        }
    }
}
