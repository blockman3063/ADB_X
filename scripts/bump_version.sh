#!/usr/bin/env bash
# bump_version.sh — single entry point for version bumps.
#
# Usage:
#   ./scripts/bump_version.sh 1.1.2 5
#   ./scripts/bump_version.sh 1.2.0 6           (auto-increment patch — soon)
#
# What it does:
#   1. Patches app/build.gradle.kts versionCode + versionName.
#   2. `git commit` only the build.gradle.kts change with a
#      conventional chore(release): bump to <name> (<code>) message.
#   3. Tags HEAD with `v<name>` and pushes branch + tag to origin.
#
# Notes:
#   - Re-uses the same keystore.properties the gradle release task
#     reads. The repo already has `keystore.properties` checked in;
#     only `temp.jks` is excluded from the index via .gitignore.
#   - GitHub Actions picks up the tag push and runs .github/workflows/
#     release.yml, which builds the release APK from the bumped
#     commit. No manual edit of build.gradle.kts before tagging.

set -eu

BASE="$(git rev-parse --show-toplevel)"
BG="$BASE/app/build.gradle.kts"
REMOTE="${REMOTE:-origin}"
BRANCH="${BRANCH:-main}"

if [ "$#" -lt 1 ]; then
  echo "usage: $0 <new_version_name> [new_version_code]"
  echo "       $0 1.1.2 5"
  echo ""
  echo "If new_version_code is omitted, it auto-increments from the"
  echo "current versionCode in app/build.gradle.kts."
  exit 64
fi

NEW_NAME="$1"
if [ "$#" -lt 2 ]; then
  CURRENT_CODE=$(awk '/versionCode/ {print $3; exit}' "$BG")
  NEW_CODE="$((CURRENT_CODE + 1))"
else
  NEW_CODE="$2"
fi

# Read current values so we can refuse to bump backwards.
CUR_NAME=$(awk -F'"' '/versionName/ {print $2; exit}' "$BG")
CUR_CODE=$(awk '/versionCode/ {print $3; exit}' "$BG")

if [ "$NEW_CODE" -le "$CUR_CODE" ]; then
  echo "refusing to bump: current versionCode is $CUR_CODE, requested $NEW_CODE"
  exit 65
fi

# Validate semver shape. Don't try to be clever — we just want to
# catch typos. Looser than the semver spec on purpose because the
# project uses 1.0.0-r11 style tags.
case "$NEW_NAME" in
  v?*) echo "strip the leading v from the version name" >&2; exit 64 ;;
  *-*) ;; # 1.0.0-r11 style
  *.*.*) ;;
  *.*) ;;
  *) echo "version name '$NEW_NAME' doesn't look like x.y or x.y.z" >&2; exit 64 ;;
esac

# Check working tree is clean before mutating the manifest. We
# don't want to capture someone else's in-flight edits into the
# release commit.
if ! git diff --quiet HEAD -- "$BG"; then
  echo "$BG has uncommitted changes — commit or stash first" >&2
  exit 66
fi

echo "bumping $CUR_NAME ($CUR_CODE) -> $NEW_NAME ($NEW_CODE)"
sed -i -E "s/versionName = \"[^\"]+\"/versionName = \"$NEW_NAME\"/" "$BG"
sed -i -E "s/versionCode = [0-9]+/versionCode = $NEW_CODE/" "$BG"

git add "$BG"
git commit -m "chore(release): bump to $NEW_NAME ($NEW_CODE)"
git push "$REMOTE" "$BRANCH"

TAG="v$NEW_NAME"
echo "tagging $TAG"
git tag -a "$TAG" -m "$TAG"
git push "$REMOTE" "$TAG"

echo "done. release.yml will build + publish the APK on the next CI run."