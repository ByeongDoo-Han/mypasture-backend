package com.example.mypasture.config

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/** Flyway가 시작되기 전에 저장소의 migration 버전 중복을 CI에서 차단합니다. */
class FlywayMigrationVersionTests {
    @Test
    fun `마이그레이션 버전은 중복되지 않는다`() {
        val versionPattern = Regex("^V([^_]+)__.+\\.sql$")
        val versions = mutableMapOf<String, MutableList<String>>()

        Files.newDirectoryStream(Path.of("src/main/resources/db/migration"), "V*__*.sql").use { files ->
            files.forEach { file ->
                val fileName = file.fileName.toString()
                val version = versionPattern.matchEntire(fileName)?.groupValues?.get(1)
                    ?: error("Flyway migration 파일명이 올바르지 않습니다: $fileName")
                versions.getOrPut(version) { mutableListOf() } += fileName
            }
        }

        val duplicates = versions.filterValues { it.size > 1 }
        assertTrue(duplicates.isEmpty(), "중복된 Flyway migration 버전이 있습니다: $duplicates")
    }
}
