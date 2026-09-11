# Notification lifecycle core

Phase 1 adds local notification state beside the existing investigation pages. It does not add
Slack, email, webhook delivery, configuration controls or human feedback actions. The core is
inactive for existing jobs. Page requests and plugin startup never activate or backfill it.

## Identity and evidence

Notification signatures use a separate v1 contract. Compiler identity includes a uniquely
resolved repository path and diagnostic discriminant; line and stage labels are context.
API, test and runtime profiles require exact discriminants and a stable module. Generic,
redacted, clipped or ambiguous identity remains unresolved and cannot group different runs.
Canonical UTF-8/NFC identity is compared in addition to its SHA-256 digest.

TrustedIdentityEvidence is the local structured producer contract. It must supply trustworthy
repository/head context and, for compiler paths, a complete bounded inventory. Existing page
changelogs are not a complete inventory. No built-in adapter guesses repository/module identity
from those changelogs or broad Pipeline stage labels. Unsupported observations remain unresolved.
CoverageEvidence names and versions the producer, run, context and affected check. Overall
SUCCESS alone cannot establish recovery. The pure reducer accepts explicit coverage evidence;
the current Jenkins adapter deliberately supplies unknown coverage until a supported producer
can establish comparable execution.

The immutable case key uses the first accepted failing run incarnation. First-bad proof can be
refined without changing that key. Repeated observations advance the occurrence count without
new events. Verified recovery ends the episode; a later recurrence opens a linked new case.
AI projection is separate and never requests analysis or upgrades deterministic proof.

## Storage and execution

NotificationInvestigationRecord is a new schema-v1 aggregate, not the derived InvestigationCase
page model. It is stored under the owning job's `bci-notifications` directory using bounded,
strict JSON and atomic replacement. JSON has no polymorphic Java type resolution. Unknown or
corrupt envelopes are quarantined. Existing Run action XML readers and signatures are unchanged.

The aggregate contains identity, lifecycle revision, semantic sequence, recent observation IDs,
redacted event snapshots, suppression, outbox state and safe audit codes. A separate durable
ingestion watermark bridges run persistence and case persistence. Reconciliation is idempotent
and limited to runs after the explicit activation boundary. It never sends anything.

The listener schedules lightweight job references; two evidence workers process a queue capped
at 250. History scans stop at 100 runs. Other limits include 256 KiB per aggregate, 200 recent
observations/audits, 50 internal candidates, a 32 KiB event, and 10,000 paths/2 MiB inventory.
Admission reserves room for lifecycle outcomes and fails closed at storage limits. Case discovery
is capped at 100 per job; the core pauses instead of deleting active evidence. Automatic archival,
retention expiry and tombstone cleanup are not implemented in this phase.

Suppression defaults are 15 seconds initial coalescing, 60 seconds material quiet time with a
five-minute maximum, 15-minute spacing, four material updates per day and twelve per lifetime.
Initial and recovery slots are reserved. Repeats, mentions and AI-only updates are off.
No stale-message replay occurs merely because a deadline, mute or process restart changes.

Outbox records are transport-neutral. A future consumer must persist its lease before submission,
preserve immutable content/identity after an attempt, and treat uncertain acceptance as
UNKNOWN_OUTCOME without automatic resend. Credential rotation is not a new semantic destination.
Phase 1 has only fake consumers in tests, so no network can occur on record load.

## Compatibility boundary

No InvestigationAction, InvestigationRunListener, FailureSignal, HistoryEvidence, ManifestEvidence,
AI provider implementation, Jelly, CSS or JavaScript is changed. Default-off jobs retain their
released behavior. Human confirmation, permissions for feedback, external destination approval,
credential resolution, delivery workers and real transport acceptance remain later phases.

The internal administrator-checked activation hook exists for integration testing and future
policy integration. It is not a web endpoint or a supported user configuration surface.
The phase acceptance report records measured test results, supported provenance scope and
remaining limitations; this document does not claim universal SCM or Pipeline coverage.
