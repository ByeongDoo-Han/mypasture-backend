import unittest

from select_tests import select


class SelectTestsTests(unittest.TestCase):
    def test_ai_change_does_not_select_unrelated_domains(self):
        result = select(["src/main/kotlin/com/example/mypasture/ai/application/AiService.kt"])
        self.assertEqual(result["unit"], ["com.example.mypasture.ai.*"])
        self.assertTrue(result["postgres"])
        self.assertFalse(any("BibleCursor" in name for name in result["postgres"]))

    def test_docs_only_does_not_run_application_tests(self):
        result = select(["docs/guide.md", "AGENTS.md"])
        self.assertEqual(result["unit"], [])
        self.assertFalse(result["runtime"])

    def test_migration_requires_full_and_postgres(self):
        result = select(["src/main/resources/db/migration/V99__change.sql"])
        self.assertEqual(result["unit"], ["*"])
        self.assertTrue(result["migration"])
        self.assertTrue(result["postgres"])

    def test_unknown_runtime_path_requires_full(self):
        result = select(["unknown-runtime-file"])
        self.assertEqual(result["unit"], ["*"])

    def test_missing_domain_suite_fails(self):
        with self.assertRaisesRegex(ValueError, "No test suite"):
            select(["src/main/kotlin/com/example/mypasture/new_domain/Service.kt"])

    def test_reading_uses_its_integration_suites(self):
        result = select(["src/main/kotlin/com/example/mypasture/reading/application/ReadingService.kt"])
        self.assertIn("*ReadingActivityIntegrationTests", result["unit"])
        self.assertNotIn("*", result["unit"])

    def test_single_terraform_root_skips_other_roots(self):
        result = select(["infra/terraform/environments/staging/main.tf"])
        self.assertEqual(result["terraform_roots"], ["environments/staging"])
        self.assertEqual(result["unit"], [])

    def test_single_test_file_runs_only_that_test(self):
        result = select(["src/test/kotlin/com/example/mypasture/ai/AiCitationValidatorTests.kt"])
        self.assertEqual(result["unit"], ["com.example.mypasture.ai.AiCitationValidatorTests"])


if __name__ == "__main__":
    unittest.main()
