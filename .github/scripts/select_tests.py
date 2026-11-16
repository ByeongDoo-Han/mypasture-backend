#!/usr/bin/env python3
"""Choose backend checks from changed paths; unknown runtime paths fail safe to full coverage."""

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SOURCE_PREFIXES = (
    "src/main/kotlin/com/example/mypasture/",
    "src/test/kotlin/com/example/mypasture/",
)
TEST_ROOT = ROOT / "src/test/kotlin/com/example/mypasture"
POSTGRES_TESTS = {
    "auth": ["AdminEmailLoginControllerTests", "NicknameMigrationPostgresTests"],
    "bible": ["BibleCursorSearchControllerTests", "BibleCommentaryPreloadPostgresTests", "ScriptureCopyControllerTests", "ScriptureCopyLegacyInsertPostgresTests"],
    "ai": ["AiAnswerHistoryTests", "AiHistoryControllerTests", "AiContextSnapshotTests", "AiContextDeletionGuardPostgresTests"],
    "user": ["ActivityCalendarControllerTests", "ReadingActivityIntegrationTests", "AvatarPlatformControllerTests", "AvatarOutfitControllerTests", "AvatarOutfitMigrationPostgresTests"],
    "achievement": ["AchievementControllerTests", "AchievementCatalogTests", "AchievementErasureGuardPostgresTests"],
    "daily_word": ["DailyWordRotationPostgresTests", "DailyWordRotationMigrationPostgresTests"],
    "quiet_time": ["QuietTimeSharedQuestionTests", "QuietTimePastureMembershipPostgresTests"],
    "worship": ["WorshipRecordPostgresTests"],
    "prayer": ["PrayerFlowPostgresTests"],
    "reading": ["ReadingActivityIntegrationTests", "ActivityCalendarControllerTests"],
}
ADJACENT_TESTS = {
    "reading": ["*ReadingActivityIntegrationTests", "*ActivityCalendarControllerTests"],
    "attendance": ["*MvpDailyFlowTests", "*QuietTimeControllerTests"],
}
TERRAFORM_ROOTS = ("bootstrap/state", "bootstrap/github-actions", "environments/staging", "environments/prod")


def select(paths: list[str], force_full: bool = False) -> dict:
    domains: set[str] = set()
    direct_tests: set[str] = set()
    full = force_full
    runtime = force_full
    container = force_full
    terraform_roots: set[str] = set(TERRAFORM_ROOTS if force_full else ())
    migration = False
    reasons: list[str] = []
    for path in paths:
        if path.startswith("infra/terraform/"):
            root = next((root for root in TERRAFORM_ROOTS if path.startswith(f"infra/terraform/{root}/")), None)
            terraform_roots.update([root] if root else TERRAFORM_ROOTS)
            continue
        if path == "Dockerfile" or path == "build.gradle.kts" or path == "settings.gradle.kts" or path.startswith("gradle/"):
            container = True
            runtime = True
            full = True
            reasons.append(path)
            continue
        if path.startswith("src/main/resources/db/migration/"):
            migration = runtime = container = full = True
            reasons.append(path)
            continue
        if path.startswith("src/main/resources/application") or path.startswith("src/test/resources/application"):
            runtime = container = full = True
            reasons.append(path)
            continue
        prefix = next((p for p in SOURCE_PREFIXES if path.startswith(p)), None)
        if prefix:
            if prefix.startswith("src/test/") and Path(path).stem.endswith("Tests"):
                direct_tests.add("com.example.mypasture." + path[len(prefix):].removesuffix(".kt").replace("/", "."))
                continue
            domain = path[len(prefix):].split("/", 1)[0]
            if "." in domain or domain in {"common", "config", "testing", "admin_audit"}:
                full = True
                reasons.append(path)
            else:
                domains.add(domain)
            if prefix.startswith("src/main/"):
                runtime = container = True
            continue
        if path.startswith("src/main/resources/"):
            runtime = container = full = True
            reasons.append(path)
            continue
        if path.startswith("src/test/resources/"):
            full = True
            reasons.append(path)
            continue
        if path.startswith(("docs/", "http/", ".github/")) or path in {"AGENTS.md", "README.md", ".gitignore"}:
            continue
        # An unclassified production path must never silently skip checks.
        full = runtime = container = True
        reasons.append(path)

    existing = {p.relative_to(TEST_ROOT).parts[0] for p in TEST_ROOT.glob("*/**/*Tests.kt")}
    missing = domains - existing - ADJACENT_TESTS.keys()
    if missing:
        raise ValueError(f"No test suite for changed domain(s): {', '.join(sorted(missing))}")
    unit = ["*"] if full else sorted(direct_tests) + [f"com.example.mypasture.{name}.*" for name in sorted(domains & existing)] + [test for name in sorted(domains) for test in ADJACENT_TESTS.get(name, [])]
    pg = sorted({name for domain in (POSTGRES_TESTS if full else domains) for name in POSTGRES_TESTS.get(domain, [])})
    pg_classes = {name for classes in POSTGRES_TESTS.values() for name in classes}
    pg.extend(test for test in direct_tests if test.rsplit(".", 1)[-1] in pg_classes or "PostgresTests" in test)
    if full:
        pg.extend(f"*{path.stem}" for path in TEST_ROOT.rglob("*PostgresTests.kt") if path.stem not in pg)
    return {
        "unit": unit,
        "postgres": [name if name.startswith("*") else f"*{name}" for name in pg],
        "runtime": runtime,
        "container": container,
        "terraform_roots": sorted(terraform_roots),
        "migration": migration,
        "reason": ", ".join(reasons) if reasons else ", ".join(sorted(domains)) or "문서/계약/CI 설정만 변경",
    }


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--paths-file", type=Path, required=True)
    parser.add_argument("--full", action="store_true")
    args = parser.parse_args()
    paths = [line.strip() for line in args.paths_file.read_text().splitlines() if line.strip()]
    print(json.dumps(select(paths, args.full), ensure_ascii=False))


if __name__ == "__main__":
    main()
