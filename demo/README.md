# Local demo scenario

This walks through a minimal, reproducible "build passes, then a change breaks it" scenario
using a plain local Git repository and a Jenkins Freestyle job - no external services needed.

## 1. Start Jenkins with the plugin installed

From the repository root:

```bash
./mvnw hpi:run
```

Wait for `Jenkins is fully up and running`, then open http://localhost:8080/jenkins/.

## 2. Create a tiny demo Git repository

Run the setup script, which creates a local git repo at `demo/work/demo-repo` with one commit
that builds successfully:

```bash
bash demo/setup-demo-repo.sh
```

(On Windows, run it from Git Bash, which ships with Git for Windows.)

This repository contains a single file, `build.sh`, that a Jenkins build step will execute.
The first commit's version of `build.sh` exits `0` (success).

## 3. Create the Jenkins Freestyle job

1. **New Item → Freestyle project**, name it `demo-regression`.
2. **Source Code Management → Git**, repository URL:
   `file:///absolute/path/to/demo/work/demo-repo` (use the absolute path printed by the setup
   script).
3. **Build Steps → Execute shell** (or **Execute Windows batch command** on Windows):
   ```
   bash build.sh
   ```
   (Windows: `bash build.sh` also works if Git Bash is on `PATH`, or translate `build.sh`'s
   two lines into a `.bat` step.)
4. Save, then **Build Now**. This is build **#1** - it should succeed.

## 4. Introduce a regression

Run the second script, which commits a change to `build.sh` that makes it fail (simulating,
e.g., a bad dependency bump or a broken script edit):

```bash
bash demo/break-demo-repo.sh
```

Back in Jenkins, click **Build Now** again. This is build **#2** - it fails.

## 5. See the investigation

Open build **#2**'s page. You'll see a **"Build Change Investigation"** link in the sidebar
(added automatically by the plugin because the build finished worse than `SUCCESS`). It shows:

- **Last Successful Build**: #1
- **Changes since last success**: the one commit from `break-demo-repo.sh`, with its message,
  author, and the changed file (`build.sh`)
- **Failure log excerpt**: the relevant lines from the failed shell step

If you've configured AI analysis (see [README.md](../README.md#configuration)) - for example,
pointing it at a real OpenAI-compatible endpoint - click **"Run AI Analysis"** to see the AI
correctly identify the `build.sh` change as the likely cause, citing the commit message and the
log's exit-code failure as its evidence.

## Cleanup

```bash
rm -rf demo/work
```

And delete the `demo-regression` job from Jenkins if you no longer need it.
