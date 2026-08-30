# Release Checklist

This documents the exact steps to take this plugin from a local build to an installable
release, and what would additionally be required to publish it on the official Jenkins plugin
site. **No step here is performed automatically** - each is a manual action for a maintainer
with the appropriate access.

## 1. Pre-release verification (do this every time)

```bash
./mvnw clean verify
```

Confirm:
- [ ] `BUILD SUCCESS`
- [ ] All tests passed (check the printed summary: `Tests run: X, Failures: 0, Errors: 0, Skipped: 0`)
- [ ] `target/build-change-investigator.hpi` exists
- [ ] No secrets, API keys, or personal credentials appear anywhere in `git diff` / `git status`
- [ ] `CHANGELOG.md` has an entry for this version (move "Unreleased" items under a new
      version heading with today's date)

## 2. Versioning and release (Continuous Delivery)

This plugin uses Jenkins' standard CD setup, **not** manual version bumps or tags:

- `pom.xml`'s `<version>` is `${changelist}`, backed by the `changelist` property
  (`999999-SNAPSHOT` for local/dev builds) and the `git-changelist-maven-extension`
  (`.mvn/extensions.xml` + `.mvn/maven.config`), which computes the real released version
  (`<build-number>.v<git-sha>`) automatically from CI/CD - never edit `<version>` by hand.
- Releases are produced by `.github/workflows/cd.yaml`, which calls the shared
  `jenkins-infra/github-reusable-workflows` `maven-cd.yml` workflow. In the default
  configuration here (`check_run` trigger), a release is drafted automatically whenever CI
  passes on `master`/`main` and there are merged pull requests of interest; releases can also
  be triggered manually via **Actions → cd → Run workflow**.
- The published artifact goes to `repo.jenkins-ci.org` (Artifactory), which feeds the Jenkins
  Update Center directly - there is no separate "attach the .hpi to a GitHub Release" step to
  perform manually.
- Update `CHANGELOG.md` under an "Unreleased" heading as part of the normal PR process; there
  is no manual "remove -SNAPSHOT" step.

See https://www.jenkins.io/doc/developer/publishing/releasing-cd/ for the full mechanism.

## 3. Installing the HPI manually on a Jenkins controller

1. Download the `.hpi` from the GitHub Release (or use your local `target/*.hpi` build).
2. In Jenkins: **Manage Jenkins → Plugins → Advanced settings** tab → **Deploy Plugin** section
   → choose the file → **Deploy**.
   - Alternatively, copy the file directly into `$JENKINS_HOME/plugins/build-change-investigator.hpi`
     and restart Jenkins.
3. Restart Jenkins when prompted.
4. Verify: **Manage Jenkins → Plugins → Installed plugins**, search for "Build Change
   Investigator", confirm it's listed and enabled.
5. Verify: **Manage Jenkins → System**, confirm the "Build Change Investigator" section appears.

## 4. Testing on a Jenkins controller

- [ ] Run a job that will fail (or use the [demo scenario](demo/README.md)); confirm the
      "Build Change Investigation" link appears on the build page.
- [ ] Confirm observed evidence renders correctly with AI analysis left disabled (the default).
- [ ] Configure AI analysis with a real or test OpenAI-compatible endpoint, use **Test
      Connection**, then run a real investigation and confirm the AI section renders and is
      visually distinct from the observed-evidence section.
- [ ] Confirm a user without the `RunChangeInvestigationAnalysis` permission does not see the
      "Run AI Analysis" button and is denied if they hit the endpoint directly.
- [ ] Restart Jenkins and confirm a previously-computed investigation (including any cached AI
      result) still renders correctly on an old build - this proves the `RunAction2`
      persistence (`onLoad`/`onAttached`) is working.

## 5. Future Jenkins plugin-site (Update Center) publication requirements

This repository has **not** been published to the official Jenkins Update Center yet. An
[official hosting request](https://github.com/jenkins-infra/repository-permissions-updater/issues/5249)
is open; remaining steps for a maintainer with the appropriate access:

1. Get the hosting request approved (`jenkinsci/build-change-investigator-plugin`). This
   repository already ships everything the Jenkins Hosting Checker requires: the
   `${changelist}` CD version scheme, `Jenkinsfile`, `.mvn/maven.config` +
   `.mvn/extensions.xml`, `.github/workflows/cd.yaml` and `jenkins-security-scan.yml`,
   `.github/CODEOWNERS`, and a Renovate config.
2. Confirm CI is green on `ci.jenkins.io` (the `Jenkinsfile` here uses the standard
   `buildPlugin()` step from `jenkins-infra/pipeline-library`).
3. Once approved, the users listed in the hosting request need Jira
   (issues.jenkins.io) and Artifactory (repo.jenkins-ci.org) accounts for release permission
   to take effect - the Hosting Checker re-syncs this hourly.
4. The actual release then happens automatically via `.github/workflows/cd.yaml` (see
   [section 2](#2-versioning-and-release-continuous-delivery) above) - no manual
   `mvn release:prepare release:perform` step is needed.
5. Add the plugin's metadata (`categories`, description, etc.) as required by the plugin site.
6. Ensure `SECURITY.md` reporting process aligns with the
   [Jenkins Security team's process](https://www.jenkins.io/security/) once officially listed.

**Do not perform step 1 (accepting the hosting request) or push to trigger a release without
explicit authorization** - these affect shared, organization-wide infrastructure.

## Known limitations to disclose at release time

See [README.md#limitations](README.md#limitations) - copy the relevant points into the GitHub
Release notes so users have them up front.

## Files to manually review before public release

- [ ] `pom.xml` - confirm `<scm>` and `<url>` point to the actual repository, not placeholders.
      (There is deliberately no `<developers>` block - the Jenkins Hosting Checker fetches
      maintainer information from the repository/update-site history instead.)
- [ ] `README.md` - confirm the GitHub repository URL matches the actual location.
- [ ] `SECURITY.md` - confirm the security advisory / contact link is correct.
- [ ] No `.env`, credentials, or personal file paths were accidentally committed
      (`git log --all --full-history -- '*.env' '*credential*'` as a sanity check).
