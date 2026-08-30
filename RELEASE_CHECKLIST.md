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

## 2. Version bump

Edit `pom.xml`:
```xml
<version>X.Y.Z</version>   <!-- remove -SNAPSHOT for a release build -->
```

## 3. Tag and create a GitHub Release

```bash
git add pom.xml CHANGELOG.md
git commit -m "Release X.Y.Z"
git tag -a vX.Y.Z -m "Release X.Y.Z"
git push origin main --tags
```

Then, on GitHub:
1. Go to **Releases → Draft a new release**.
2. Choose the `vX.Y.Z` tag.
3. Title: `X.Y.Z`. Body: copy the relevant section from `CHANGELOG.md`.
4. Attach `target/build-change-investigator.hpi` as a release asset.
5. Publish.

After publishing, bump `pom.xml` back to the next `-SNAPSHOT` version for continued development
and commit that separately.

## 4. Installing the HPI manually on a Jenkins controller

1. Download the `.hpi` from the GitHub Release (or use your local `target/*.hpi` build).
2. In Jenkins: **Manage Jenkins → Plugins → Advanced settings** tab → **Deploy Plugin** section
   → choose the file → **Deploy**.
   - Alternatively, copy the file directly into `$JENKINS_HOME/plugins/build-change-investigator.hpi`
     and restart Jenkins.
3. Restart Jenkins when prompted.
4. Verify: **Manage Jenkins → Plugins → Installed plugins**, search for "Build Change
   Investigator", confirm it's listed and enabled.
5. Verify: **Manage Jenkins → System**, confirm the "Build Change Investigator" section appears.

## 5. Testing on a Jenkins controller

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

## 6. Future Jenkins plugin-site (Update Center) publication requirements

This repository has **not** been published to the official Jenkins Update Center. To do so in
the future, a maintainer with the appropriate access would need to:

1. Ensure the repository lives under the `jenkinsci` GitHub organization (request via the
   [Jenkins hosting request process](https://www.jenkins.io/doc/developer/plugin-governance/hosting-a-plugin/)
   if it does not already).
2. Confirm CI is green via the shared Jenkins infrastructure (`Jenkinsfile` using the
   `buildPlugin()` shared library step - not included in this initial scaffold and would need
   to be added).
3. Request permission for the plugin's Maven coordinates (`io.jenkins.plugins:build-change-investigator`)
   via the [Artifactory permissions request](https://www.jenkins.io/doc/developer/publishing/requesting-hosting/).
4. Release via `mvn release:prepare release:perform` (or the recommended
   [CD/JEP-229](https://www.jenkins.io/doc/developer/publishing/releasing-cd/) automated
   pipeline) so the artifact is deployed to `repo.jenkins-ci.org`, which feeds the Update
   Center.
5. Add the plugin's metadata (`categories`, description, etc.) as required by the plugin site.
6. Ensure `SECURITY.md` reporting process aligns with the
   [Jenkins Security team's process](https://www.jenkins.io/security/) if the plugin will be
   officially listed.

**Do not perform steps 1-4 without explicit authorization** - they publish to shared,
organization-wide infrastructure.

## Known limitations to disclose at release time

See [README.md#limitations](README.md#limitations) - copy the relevant points into the GitHub
Release notes so users have them up front.

## Files to manually review before public release

- [ ] `pom.xml` - confirm `<developers>`, `<scm>`, and `<url>` point to the actual repository
      and maintainer(s), not placeholders.
- [ ] `README.md` - confirm the GitHub repository URL matches the actual location.
- [ ] `SECURITY.md` - confirm the security advisory / contact link is correct.
- [ ] No `.env`, credentials, or personal file paths were accidentally committed
      (`git log --all --full-history -- '*.env' '*credential*'` as a sanity check).
