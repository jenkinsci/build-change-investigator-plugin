#!/usr/bin/env bash
# Creates a minimal local Git repository for the Build Change Investigator demo.
# See demo/README.md for the full walkthrough.
set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$DEMO_DIR/work/demo-repo"

rm -rf "$REPO_DIR"
mkdir -p "$REPO_DIR"
cd "$REPO_DIR"

git init -q
git config user.email "demo@example.invalid"
git config user.name "Demo User"

cat > build.sh <<'EOF'
#!/usr/bin/env bash
echo "Running the demo build step..."
echo "All checks passed."
exit 0
EOF
chmod +x build.sh

git add build.sh
git commit -q -m "Initial working build script"

echo "Demo repository created at: $REPO_DIR"
echo "Use this as the Jenkins job's Git repository URL:"
echo "  file://$REPO_DIR"
