# Changelog

All notable changes to this project are documented in this file.

## [Unreleased]

## [1.0.0] - 2026-08-29

Initial release.

### Added

- Deterministic "OBSERVED EVIDENCE" collection for any build that finishes worse than
  `SUCCESS`: previous-successful-build discovery, SCM-agnostic changelog collection
  (commits/authors/messages/changed files), a reduced and secret-redacted failure log excerpt,
  and agent/duration/timing metadata - all clearly marked when unavailable rather than guessed.
- Optional "AI ASSESSMENT" powered by any OpenAI-compatible chat-completions endpoint,
  administrator-configured under **Manage Jenkins → System**, with credentials handled via the
  Jenkins Credentials plugin. Disabled by default.
- `Build Change Investigation` action on the build page for builds with observed evidence, with
  a "Run AI Analysis" / "Re-run AI Analysis" button gated by a dedicated
  `RunChangeInvestigationAnalysis` permission (implied by `Item.BUILD`).
- AI results are cached per build (persisted with the build) so viewing a build page never
  triggers a new AI API call - only an explicit click does.
- Global configuration: enable/disable, base URL, model, credential, max log context
  characters, connection timeout, temperature, additional HTTP headers, and a Test Connection
  button.
