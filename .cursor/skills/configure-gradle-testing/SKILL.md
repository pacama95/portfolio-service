---
name: configure-gradle-testing
description: Configures and repairs the Gradle testing pipeline for Quarkus services, including unit tests, JaCoCo verification, integration tests, build ordering, and console/file summaries. Use when splitting Gradle test configuration, fixing integration-test discovery, wiring tests into build, or adding build reports.
---

# Configure Gradle Testing

Apply the project's Gradle testing convention without changing test behavior or weakening coverage.

## Workflow

1. Inspect `build.gradle`, applied Gradle scripts, Quarkus tasks, source sets, and test locations.
2. Keep plugins, dependencies, repositories, basic project information, Java configuration, and packaging in `build.gradle`.
3. Create or update `gradle/testing.gradle`, then apply it from `build.gradle`.
4. Configure unit tests and JaCoCo:
   - Unit tests use `sourceSets.test`.
   - `test` finalizes `jacocoTestReport`.
   - Coverage verification depends on the report.
   - Preserve project exclusions.
   - Require at least 90% line and branch coverage.
5. Configure integration tests:
   - Use Quarkus's `sourceSets.integrationTest`; do not merge it into `test`.
   - Set its output classes and runtime classpath on a dedicated `Test` task.
   - Keep the task enabled so IntelliJ and filtered Gradle runs work without properties.
6. Configure `build` to include `integrationTest`.
   - Unit tests and coverage verification run first.
   - Set `integrationTest.mustRunAfter jacocoTestCoverageVerification`.
7. Finalize `build` with an always-running summary task.
   - Read JUnit XML for unit and integration totals.
   - Read report-wide line and branch counters from JaCoCo XML.
   - Print the summary and write `build/reports/build-summary.txt`.
   - Include overall build, unit-test, coverage-verification, and integration-test status.

## Verification

Run these checks:

```bash
./gradlew integrationTest --tests fully.qualified.IntegrationTestClass
./gradlew clean build
```

Confirm the filtered class runs, coverage thresholds pass, integration tests run after coverage, the console prints the summary, and `build/reports/build-summary.txt` matches it.

Do not report completion if Gradle says `NO-SOURCE`, no matching tests, or the summary task fails.
