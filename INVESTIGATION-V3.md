# Investigation experience v3 — implementation notes

The approved comparison-led layout is retained. Jenkins owns the navigation tasks and job widgets; the plugin does not render synthetic Build History rows. Below 900 pixels, the same native navigation and widgets sit in an expandable disclosure so the investigation appears early.

## Evidence and limits

- Failure extraction uses only retained, redacted log evidence. Maven/Gradle compiler diagnostics, Java exceptions, stack frames, test failures and API linkage errors have structured fields. Generic failures retain a bounded fallback and do not receive a specific signature.
- Default display contains the diagnostic and relevant stack/symbol lines. Original supporting lines and the bounded surrounding excerpt remain available, as does native Console Output.
- Raw log processing stops at 50,000 lines or 4,000,000 characters. Individual retained lines are capped at 8,000 characters. The configured excerpt bound still applies.
- Successful-baseline and SCM traversal are capped at 200 runs; change entries at 500, total retained paths at 2,000 and ranked records at 2,000. Native SCM history remains an escape hatch for capped evidence.
- Relevance uses direct source-path matching, explicit build-task module/path matching and weaker stage/configuration overlap. Every retained record carries a reason, limitation and next check. A matching file does not prove its diagnostic line changed. Module overlap does not prove runtime artifact ownership.
- First bad requires adjacent build numbers and FAILURE results with the same specific signature back to SUCCESS. UNSTABLE, ABORTED, NOT_BUILT, missing results, missing builds, missing signatures and different signatures leave the boundary unknown. At most 100 runs are inspected at completion.
- Similar failures use exact structured signatures and diagnostic text, scoped to this job and beyond a successful boundary. They are context, never proof of the same cause. No prior resolution is invented.
- Build-time manifest snapshots read root pom.xml and at most 19 explicitly declared module manifests for supported freestyle workspaces. Each file is limited to 16 KiB; unsafe paths and symlinks are rejected. XML external entities and DTDs are disabled. Only declared artifact/version information is retained, bounded and redacted. Properties are not resolved, inherited versions are not guessed, and runtime artifacts are not verified. Pipeline or unavailable workspaces degrade to unavailable content.
- Ordinary viewing uses persisted evidence. No provider, SCM server or workspace request occurs on page load. Explicit comparison is a bounded local collection for two completed builds of the same accessible job, cached in memory for eight boundaries. A baseline must precede the target within 100 build numbers.

## Compatibility and safety

The released provider configuration, SDK alignment and error taxonomy are preserved. Public AI failure messages use category-specific guidance; raw provider diagnostics are excluded from persisted failures and plugin logs. AI remains explicit POST with the existing permission check. Evidence reference IDs are shown only when returned in supporting evidence; older responses without IDs retain their textual citations.

Comparison requires Item.READ and never accepts a job name from the request. Copy uses an escaped, bounded plain-text case bundle rather than full console output; clipboard denial exposes a selectable fallback. SCM/log/provider output remains escaped by Jelly, with no raw HTML injection.

Older investigations without the new case fields derive a conservative view from stored evidence and retain unknown history. No migration triggers an external call. Build-time snapshots are separately persisted; missing snapshots preserve the approved comparison layout with an explicit evidence gap.

## AI evidence scope

Verified first-bad investigations submit a copy of the saved evidence containing the same narrowed changes displayed by the deterministic investigation. The failure signal and build metadata remain from the current build, explicitly identified in the prompt and UI. Original evidence is preserved. Historical assessments retain their original full-window scope until rerun. The page reads assessment, scope and references as one immutable snapshot, including while a retry is pending. Arbitrary comparisons do not relabel or change the saved automatic assessment.

## Decision gate

The frozen implementation was checkpointed before integration with hardened master. Disposable demo repositories contain synthetic commits solely to exercise actual Jenkins changelogs. The combined implementation requires runtime screenshots and validation review before a pull request or release.
