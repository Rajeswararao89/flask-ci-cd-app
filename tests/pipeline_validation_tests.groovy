#!/usr/bin/env groovy
/**
 * tests/pipeline_validation_tests.groovy
 *
 * Validation suite for the CI/CD pipeline.
 * Run with: groovy tests/pipeline_validation_tests.groovy
 *
 * These tests verify the helper functions in the Jenkinsfile / shared library
 * WITHOUT requiring a live Jenkins instance.
 */

// ─── Minimal stubs so Groovy closures run outside Jenkins ────────────────────

def fileExistsMap = [:]   // populated per test scenario
def fileExists(String path) { fileExistsMap.containsKey(path) && fileExistsMap[path] }
def error(String msg)        { throw new RuntimeException("Pipeline error: ${msg}") }

// ─── Functions under test (copied from Jenkinsfile / cicdUtils.groovy) ───────

def detectBuildCommand() {
    if (fileExists('pom.xml'))             return 'mvn clean package -DskipTests -B'
    if (fileExists('build.gradle'))        return './gradlew clean build -x test'
    if (fileExists('package.json'))        return 'npm ci && npm run build'
    if (fileExists('go.mod'))              return 'go build ./...'
    if (fileExists('requirements.txt'))    return 'pip install -r requirements.txt'
    if (fileExists('Makefile'))            return 'make build'
    error "No recognised build file found."
}

def detectTestCommand() {
    if (fileExists('pom.xml'))             return 'mvn test -B'
    if (fileExists('build.gradle'))        return './gradlew test'
    if (fileExists('package.json'))        return 'npm test -- --ci --coverage'
    if (fileExists('go.mod'))              return 'go test ./... -v'
    return 'echo "No test runner detected – skipping"'
}

def envFromBranch(String branch) {
    if (branch ==~ /^(main|master)$/)  return 'prod'
    if (branch ==~ /^release\/.+$/)    return 'staging'
    return 'dev'
}

// ─── Simple test runner ───────────────────────────────────────────────────────

int passed = 0
int failed = 0

def test(String name, Closure body) {
    try {
        body()
        println "  ✅  PASS  ${name}"
        passed++
    } catch (AssertionError | Exception e) {
        println "  ❌  FAIL  ${name}"
        println "           ${e.message}"
        failed++
    }
}

def assert_eq(def actual, def expected) {
    assert actual == expected : "Expected '${expected}', got '${actual}'"
}

// ─── Test cases ───────────────────────────────────────────────────────────────

println "\n=== detectBuildCommand() ==="

test("Maven project") {
    fileExistsMap = ['pom.xml': true]
    assert_eq detectBuildCommand(), 'mvn clean package -DskipTests -B'
}

test("Gradle project") {
    fileExistsMap = ['build.gradle': true]
    assert_eq detectBuildCommand(), './gradlew clean build -x test'
}

test("Node.js project") {
    fileExistsMap = ['package.json': true]
    assert_eq detectBuildCommand(), 'npm ci && npm run build'
}

test("Go module project") {
    fileExistsMap = ['go.mod': true]
    assert_eq detectBuildCommand(), 'go build ./...'
}

test("Python project") {
    fileExistsMap = ['requirements.txt': true]
    assert_eq detectBuildCommand(), 'pip install -r requirements.txt'
}

test("Makefile project") {
    fileExistsMap = ['Makefile': true]
    assert_eq detectBuildCommand(), 'make build'
}

test("Unknown project raises error") {
    fileExistsMap = [:]
    try {
        detectBuildCommand()
        assert false : "Should have thrown"
    } catch (RuntimeException e) {
        assert e.message.contains("No recognised build file")
    }
}

println "\n=== detectTestCommand() ==="

test("Maven test command") {
    fileExistsMap = ['pom.xml': true]
    assert_eq detectTestCommand(), 'mvn test -B'
}

test("Gradle test command") {
    fileExistsMap = ['build.gradle': true]
    assert_eq detectTestCommand(), './gradlew test'
}

test("Node test command") {
    fileExistsMap = ['package.json': true]
    assert_eq detectTestCommand(), 'npm test -- --ci --coverage'
}

test("Go test command") {
    fileExistsMap = ['go.mod': true]
    assert_eq detectTestCommand(), 'go test ./... -v'
}

test("Fallback test command when no runner detected") {
    fileExistsMap = [:]
    assert detectTestCommand().contains("No test runner")
}

println "\n=== envFromBranch() ==="

test("main → prod") {
    assert_eq envFromBranch('main'), 'prod'
}

test("master → prod") {
    assert_eq envFromBranch('master'), 'prod'
}

test("release/1.2.3 → staging") {
    assert_eq envFromBranch('release/1.2.3'), 'staging'
}

test("release/hotfix → staging") {
    assert_eq envFromBranch('release/hotfix'), 'staging'
}

test("feature/my-feature → dev") {
    assert_eq envFromBranch('feature/my-feature'), 'dev'
}

test("develop → dev") {
    assert_eq envFromBranch('develop'), 'dev'
}

test("arbitrary branch → dev") {
    assert_eq envFromBranch('some-random-branch'), 'dev'
}

// ─── Results ──────────────────────────────────────────────────────────────────

println "\n─────────────────────────────────────────"
println "Results: ${passed} passed, ${failed} failed"
println "─────────────────────────────────────────\n"

if (failed > 0) System.exit(1)
