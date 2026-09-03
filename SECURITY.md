# Security Policy

## Reporting a vulnerability

This plugin follows the [Jenkins project's security vulnerability reporting process](https://www.jenkins.io/security/reporting/).
Please do not report security issues in the public issue tracker or a public GitHub issue on
this repository.

Please [report security vulnerabilities in the Jenkins issue tracker under the SECURITY project](https://issues.jenkins.io/secure/CreateIssueDetails!init.jspa?pid=10180&issuetype=10103).
This project is configured so that only the reporter and the Jenkins security team can see the
details, which lets us work on a fix and deliver it before the method of attack becomes
well-known. If you are unable to use the issue tracker, you can instead email the private
Jenkins security team mailing list: `jenkinsci-cert@googlegroups.com`.

The Jenkins security team will then work with the maintainers of this plugin to get the issue
resolved, and will coordinate disclosure timing. See
[Reporting Security Vulnerabilities on jenkins.io](https://www.jenkins.io/security/reporting/)
for further details on scope, issue handling, and the disclosure process.

## What data this plugin handles

Before enabling AI analysis, understand what leaves your Jenkins controller:

- **Deterministic evidence collection (always on, no network calls to AI):** job name, build
  numbers, result, timestamps, duration, agent/node name, SCM changelog data (commit IDs,
  authors, messages, changed file paths) as reported by Jenkins' own APIs, and a *reduced,
  redacted* excerpt of the failed build's console log.
- **AI analysis (opt-in, administrator-controlled):** the evidence bundle above is sent to
  whichever AI provider an administrator configures under **Manage Jenkins → System → Build
  Change Investigator** - one of the native providers (OpenAI, Anthropic Claude, AWS Bedrock,
  Azure OpenAI, Google Gemini, Ollama) or a Generic OpenAI-compatible endpoint; see
  [Supported AI providers](README.md#supported-ai-providers) for exactly what each one sends
  and how. Nothing is sent anywhere until an administrator both enables AI analysis *and* a
  user with the `RunChangeInvestigationAnalysis` permission clicks "Run AI Analysis" on a
  specific build.

### What is deliberately never sent

- The full, unredacted console log. Only a bounded, keyword-selected excerpt is used (see
  `LogReducer`), capped at the administrator-configured character limit.
- Jenkins credentials, secrets, or environment variables.
- Workspace file contents (only SCM-reported *paths* of changed files, never file contents).
- The AI provider's own API token is never logged, echoed back in any UI, or persisted outside
  Jenkins' credential store.

### Secret redaction is best-effort, not a guarantee

`SecretRedactor` strips common patterns (bearer tokens, `password=`/`token=`-style assignments,
AWS/GitHub/Slack-style keys, JWTs, PEM private key blocks) from any log text before it is
included in evidence or sent to an AI provider. **This is not, and cannot be, exhaustive.**
Build logs can contain arbitrary text, including secrets in formats this plugin does not
recognize. Administrators are responsible for:

- Reviewing what a representative build's console output looks like before enabling AI analysis
  for that job/folder.
- Choosing a trusted AI provider/endpoint, understanding that provider's own data retention and
  training policies.
- Keeping the "Max Log Context Characters" setting as small as practical for their use case.
- Restricting the `RunChangeInvestigationAnalysis` permission and the global AI configuration
  (which requires `Jenkins.ADMINISTER`) to trusted users.

## Supported versions

Only the latest released version of this plugin is supported with security fixes. See
[CHANGELOG.md](CHANGELOG.md) for release history.
