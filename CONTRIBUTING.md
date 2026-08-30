# Contributing

Thanks for considering a contribution to Build Change Investigator.

## Development requirements

- JDK 21 (required to *build* the plugin, even though the plugin itself only needs whatever
  baseline the current `jenkins.version` in `pom.xml` requires - the Jenkins plugin parent POM
  enforces this at build time).
- Maven 3.9.6+, or just use the bundled wrapper: `./mvnw` (Linux/macOS) / `mvnw.cmd` (Windows).
- No other local services are required; tests use an in-process Jenkins (via
  `jenkins-test-harness`) and an in-process mock AI HTTP server (JDK's built-in
  `com.sun.net.httpserver.HttpServer`), not real external dependencies.

## Building

```bash
./mvnw clean verify
```

This compiles, runs the full test suite, and produces `target/build-change-investigator.hpi`.

## Running locally

```bash
./mvnw hpi:run
```

Then open http://localhost:8080/jenkins/. See [README.md](README.md#local-demo) for a full
walkthrough including a reproducible "build passes, then a change breaks it" demo scenario.

## Project structure

- `io.jenkins.plugins.changeinvestigator.evidence` - deterministic evidence collection
  (previous-successful-build discovery, SCM changelog handling, log reduction, secret
  redaction). No Jenkins UI or AI code lives here; it is the most heavily unit-tested package.
- `io.jenkins.plugins.changeinvestigator.ai` - the AI request/response pipeline (prompt
  construction, the OpenAI-compatible HTTP client, response parsing). Deliberately decoupled
  from Jenkins' `GlobalConfiguration` so it can be unit tested without a running Jenkins.
- `io.jenkins.plugins.changeinvestigator.config` - the administrator-facing global
  configuration (`GlobalConfiguration` extension), including credentials lookup and the "Test
  Connection" button.
- `io.jenkins.plugins.changeinvestigator.security` - the plugin's custom permission.
- `io.jenkins.plugins.changeinvestigator` (root) - the `RunListener` that attaches evidence to
  finished builds, and the `InvestigationAction` (build page UI + `doRunAi` endpoint) that ties
  everything together.

## Code style and quality

- No unnecessary abstractions, no speculative configuration options, no half-finished code
  paths. If Jenkins can't provide a piece of evidence, say so explicitly - never guess.
- Keep the "OBSERVED EVIDENCE" (deterministic, from Jenkins) and "AI ASSESSMENT" (from the
  configured model) code paths and data types clearly separate; do not let them merge.
- Run `./mvnw clean verify` before opening a pull request; it runs the Jenkins plugin parent
  POM's standard Checkstyle/enforcer checks in addition to the test suite.

## Reporting bugs / requesting features

Open a GitHub issue on this repository. For security issues, see [SECURITY.md](SECURITY.md)
instead of a public issue.

## Pull requests

- Keep PRs focused; one logical change per PR.
- Add or update tests for any behavior change.
- Update `CHANGELOG.md` under an "Unreleased" heading.
