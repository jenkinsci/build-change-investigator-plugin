# Slack investigation notifications

## Setup

1. Install a Slack app with a bot token. Grant `chat:write`, `channels:read`, and `groups:read` for posting and the public/private channel lookup. Invite the bot to the intended channel. Add `users:read` only if you want verified mapped-user tagging.
2. Save the bot token as a Jenkins **Secret text** credential available to the controller.
3. Open **Manage Jenkins → System → Build Change Investigator — Slack**. Enable the integration, select that credential, enter a default channel such as `#jenkins-alerts`, and click **Test Connection**. A Slack channel ID is also accepted; arbitrary URLs and direct-message destinations are not.
4. Opt in each job using **Send BCI Slack notifications for this job**. Keep the global channel or select **Use a different channel for this job**.

Test Connection checks the values currently entered in the form. It checks bot identity, workspace, posting scope, and channel access without posting a message. A successful check displays the detected workspace read-only; changing or rotating the credential requires verification again. Disabled Slack integration hides the remaining settings. It is administrator-only and uses a bounded request. These checks verify available Slack metadata; workspace posting restrictions can still reject a message. Global configuration alone never subscribes jobs. Newly enabled jobs do not send historical investigations. Copied jobs start with notifications off.

## Responders

The global **Advanced** section is collapsed by default and contains only **Tag mapped responders**, a mapping count, and **Manage mappings**. The dedicated administrator page supports local search by identity, member ID, or resolved name; pagination; and adding, editing, validating, or removing mappings. Mapping rows never expand System configuration. Map an exact Git/Jenkins name or email to a stable Slack member ID. No Slack user directory is enumerated. Tagging defaults off. A suggested responder is not an assignment. Missing, invalid, or ambiguous mappings fall back to a display name and do not stop delivery. The mapping page shows the current verified workspace and a status for each row: Verified, Needs validation, Invalid, or Workspace mismatch. Healthy rows show only Edit and Remove; other rows offer validation. Adding or editing a mapping attempts verification automatically, and successful workspace verification schedules a bounded refresh of applicable mappings. A mapped user must be verified and bound to the configured Slack workspace before a mention can appear. The stable member ID remains authoritative; a resolved friendly name is optional presentation data. Old-workspace or unverified entries fall back to plain responder text. Multiple source identities may share one Slack member ID. Duplicate source identities are rejected within a workspace after trimming whitespace and ignoring case. An outage preserves the mapping with Needs validation status; Validate can refresh it later.

Only an initial investigation may contain a responder mention. Material updates, delivery retries, and recovery closures do not tag again. Editing mappings or tagging policy preserves the active investigation and its recovery thread. SCM names, commit messages, logs, and AI text cannot introduce mentions.

## Investigation lifecycle

- The first eligible failure creates one bounded Slack root with a compact diagnostic, comparison context, the most relevant change, labeled evidence strength, one limitation, and the first check to make. Full evidence remains in the linked investigation.
- Repeated failures with the same investigation evidence remain silent.
- A meaningful deterministic evidence change produces a compact reply in that root's thread.
- A later success closes the thread only when the comparable affected check is verified to have run and passed. Otherwise the investigation remains recovery-pending and Slack stays quiet.
- Successes after verified recovery stay quiet. A later recurrence starts a new episode and a new root.

Recovery currently recognizes supported Maven compiler executions in the same execution context, including a positive compilation count and matching module, goal, execution, and compiler version. It can also use an exact affected test that actually passed in published JUnit results. A skipped task, no-op compilation, unrelated passing test, or Jenkins `SUCCESS` alone is insufficient. Other tools and pipeline arrangements may remain unverified. A relevant recovery change is a candidate, not proof that a commit fixed the regression; otherwise the message says the fix is unknown.

## Optional AI

When global AI is enabled and a provider is configured, a Slack-enabled job can automatically request one analysis for the same investigation evidence scope. An existing matching result or in-flight request is reused instead of starting a duplicate. The compact AI section adds interpretation and a distinct suggested check; it does not repeat the full reasoning or deterministic recommendation. There is no additional job-level AI switch. The initial notification waits at most 30 seconds; failure or timeout falls back to deterministic evidence. If AI is disabled or unavailable, no AI request or wait is introduced. Late AI completion does not produce another Slack message or change deterministic ranking, evidence strength, or episode identity.

## Delivery and privacy

The bot uses Slack's Web API, preserving the returned root timestamp for thread replies and restart safety. Tokens stay in Jenkins Credentials, not notification records. Background delivery does not change the build result or make investigation page loads perform Slack requests.

Bounded retries handle rate limits and eligible transient failures. If Slack might have accepted a message but Jenkins cannot prove the outcome, delivery pauses instead of blindly resending. Authentication or channel failures produce a short safe status. There are no retry, restore, or re-arm controls in normal configuration.

Only bounded investigation content is sent, not the full console or changelog. Review the destination's access before opting in a job: Slack recipients will receive the selected build evidence and any included AI interpretation. Links come from trusted Jenkins/SCM metadata, and untrusted text is escaped. Local synthetic-receiver acceptance does not establish real-workspace connectivity or every Slack client's presentation.
