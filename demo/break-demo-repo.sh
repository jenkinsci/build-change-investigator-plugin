#!/usr/bin/env bash
# Commits a regression into the demo repository created by setup-demo-repo.sh.
# See demo/README.md for the full walkthrough.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$DEMO_DIR/work/demo-repo"

if [ ! -d "$REPO_DIR/.git" ]; then
  echo "Demo repository not found at $REPO_DIR - run setup-demo-repo.sh first." >&2
  exit 1
fi

cd "$REPO_DIR"

cat > build.sh <<'EOF'
#!/usr/bin/env bash
echo "Running the demo build step..."
echo "ERROR: dependency 'widget-core' failed to initialize (NoSuchMethodError)"
exit 1
EOF

git add build.sh
git commit -q -m "Bump widget-core to 3.0 (breaking change, not caught by review)"

echo "Regression committed to: $REPO_DIR"
echo "Trigger a new build in Jenkins now - it should fail."
