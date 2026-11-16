#!/usr/bin/env bash
# One-command Android release.
#
# Usage: scripts/release.sh [<version>] [--notes file] [--dry-run] [--force-tests]
#                           [--allow-branch name]
#
# <version> is the new versionName (semver without a leading v). If omitted,
# the last component of the current versionName is incremented by 1.
# versionCode always increments by 1. Pass an explicit version to change minor or major.
#
# The script:
#   - refuses a dirty worktree or a branch other than the app's main branch
#   - pulls --ff-only
#   - bumps versionCode and versionName in the app module
#   - runs unit tests (skipped when a pass marker matches this tree, unless
#     --force-tests is passed)
#   - builds the release APK / AAB
#   - verifies the signing certificate against the previous GitHub release
#   - commits, tags, pushes to the code remote(s), and publishes the GitHub
#     release (and Play, when the app is configured for it)
#
# --dry-run does everything up to, but not including, the commit, tag, push,
# GitHub release, and Play upload. Version bumps are reverted at the end so
# the tree stays clean; the artifacts are left in app/build/ for inspection.
#
# --allow-branch is only for testing the script on a feature branch. Real
# releases (and a normal dry-run) must be on the app's main branch.
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

CONF="$ROOT/scripts/release.conf"
if [[ ! -f "$CONF" ]]; then
  echo "missing $CONF" >&2
  exit 2
fi
# shellcheck disable=SC1090
source "$CONF"

: "${APP_NAME:?APP_NAME is required in release.conf}"
: "${MAIN_BRANCH:?MAIN_BRANCH is required in release.conf}"
: "${VERSION_FILE:?VERSION_FILE is required in release.conf}"
: "${CODE_REMOTES:?CODE_REMOTES is required in release.conf}"
: "${GH_RELEASE_REPO:=}"
: "${GRADLE_TEST_TASKS:=testDebugUnitTest}"
: "${GRADLE_BUILD_TASKS:=assembleRelease}"
: "${APK_PATHS:=}"
: "${AAB_PATHS:=}"
: "${PLAY_CMD:=}"
: "${TAG_PREFIX:=v}"
: "${PUSH_TAGS_TO_CODE_REMOTES:=1}"
: "${NEVER_PUSH_CODE_TO_GITHUB:=0}"
: "${PRUNE_OLD_RELEASES:=0}"
: "${PRUNE_KEEP:=5}"

usage() {
  sed -n '2,28p' "$0" >&2
  exit 2
}

VERSION=""
NOTES=""
DRY=0
FORCE_TESTS=0
ALLOW_BRANCH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --force-tests) FORCE_TESTS=1; shift ;;
    --allow-branch)
      shift
      [[ $# -gt 0 && "$1" != --* ]] || { echo "usage: --allow-branch must be followed by a branch name" >&2; exit 2; }
      ALLOW_BRANCH="$1"
      shift
      ;;
    --notes)
      shift
      [[ $# -gt 0 && "$1" != --* ]] || { echo "usage: --notes must be followed by a file path" >&2; exit 2; }
      NOTES="$1"
      shift
      ;;
    -h|--help) usage ;;
    --*) echo "unknown option: $1" >&2; usage ;;
    *)
      if [[ -z "$VERSION" ]]; then
        VERSION="$1"
        shift
      else
        echo "unexpected argument: $1" >&2
        usage
      fi
      ;;
  esac
done

if [[ -n "$NOTES" && ! -f "$NOTES" ]]; then
  echo "notes file not found: $NOTES" >&2
  exit 2
fi
if [[ -z "$NOTES" && "$DRY" -eq 0 ]]; then
  echo "a real release requires --notes <file>" >&2
  exit 2
fi

export ANDROID_HOME="${ANDROID_HOME:-/home/itsik/Android/Sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}"
export PATH="$JAVA_HOME/bin:$PATH"

APKSIGNER=""
for bt in "$ANDROID_HOME"/build-tools/*/apksigner; do
  [[ -x "$bt" ]] && APKSIGNER="$bt"
done
# The glob sorts lexically; the last existing build-tools wins (e.g. 36.0.0).

# APK_PATHS / AAB_PATHS entries are either a bare path or path=AssetName,
# where AssetName may contain {version}. Bare paths keep the Gradle filename
# (required for updaters that fetch app-release.apk).
artifact_path() {
  local spec=$1
  echo "${spec%%=*}"
}
artifact_name() {
  local spec=$1 path name
  path=${spec%%=*}
  if [[ "$spec" == *"="* ]]; then
    name=${spec#*=}
    name=${name//\{version\}/$VERSION}
    echo "$name"
  else
    basename "$path"
  fi
}

# --- git state ---

branch=$(git rev-parse --abbrev-ref HEAD)
if [[ -n "$ALLOW_BRANCH" ]]; then
  if [[ "$branch" != "$ALLOW_BRANCH" ]]; then
    echo "refuse: --allow-branch ${ALLOW_BRANCH} but this branch is ${branch}" >&2
    exit 1
  fi
elif [[ "$branch" != "$MAIN_BRANCH" ]]; then
  echo "refuse: not on ${MAIN_BRANCH} (branch is ${branch})" >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "refuse: worktree is dirty" >&2
  git status --porcelain >&2
  exit 1
fi

if [[ -z "$ALLOW_BRANCH" ]]; then
  echo "Pulling ${MAIN_BRANCH} (--ff-only)…"
  git pull --ff-only
fi

# --- versions ---
current=$(python3 - "$VERSION_FILE" <<'PY'
import re, sys, pathlib
text = pathlib.Path(sys.argv[1]).read_text()
# Prefer the first defaultConfig { ... } block so a sibling module's
# version (e.g. MyLock's smshelper) is not picked up. VERSION_FILE is
# already the app module's build.gradle.kts in every app we ship.
m = re.search(r'defaultConfig\s*\{', text)
block = text[m.start():] if m else text
# Take only until the matching close brace at the same indent is hard;
# reading the first versionCode / versionName after defaultConfig is enough.
code = re.search(r'versionCode\s*=\s*(\d+)', block)
name = re.search(r'versionName\s*=\s*"([^"]+)"', block)
if not code or not name:
    sys.stderr.write(f"could not parse versionCode/versionName in {sys.argv[1]}\n")
    raise SystemExit(1)
print(f"{code.group(1)} {name.group(1)}")
PY
)
current_code=${current%% *}
current_name=${current#* }

next_name() {
  python3 - "$1" <<'PY'
import sys, re
name = sys.argv[1]
# Strip a trailing flavour suffix like -direct before incrementing? We bump
# the versionName in defaultConfig, not the suffix. Require X.Y.Z or X.Y.
parts = name.split(".")
if not parts or not all(p.isdigit() for p in parts):
    sys.stderr.write(f"versionName {name!r} is not dotted numeric; pass an explicit version\n")
    raise SystemExit(2)
parts = [int(p) for p in parts]
if len(parts) == 2:
    parts.append(0)
# Increment the last component only. Several apps already have a patch
# greater than 99 (MyLock 0.0.129, Trivia 0.9.226), so a rollover-at-99
# rule would jump the minor version. Pass an explicit version to move
# to the next minor or major.
parts[-1] += 1
print(".".join(str(p) for p in parts))
PY
}

if [[ -z "$VERSION" ]]; then
  VERSION=$(next_name "$current_name")
fi
if ! [[ "$VERSION" =~ ^[0-9]+(\.[0-9]+)+$ ]]; then
  echo "version must be dotted numeric (got ${VERSION})" >&2
  exit 2
fi
NEW_CODE=$((current_code + 1))
TAG="${TAG_PREFIX}${VERSION}"

# Refuse if the new version is not greater than the current one when both
# have the same number of components. Allow an explicit bump that only
# changes versionCode when the caller passes the same name? No: refuse equal
# names so we never retag.
if [[ "$VERSION" == "$current_name" ]]; then
  echo "refuse: version is already ${current_name}" >&2
  exit 1
fi

ver_gt() {
  python3 - "$1" "$2" <<'PY'
import sys
def parts(s):
    return [int(p) for p in s.split(".")]
a, b = parts(sys.argv[1]), parts(sys.argv[2])
# Pad so 1.2 vs 1.2.0 compares equal-length.
n = max(len(a), len(b))
a += [0] * (n - len(a))
b += [0] * (n - len(b))
sys.exit(0 if a > b else 1)
PY
}
if ! ver_gt "$VERSION" "$current_name"; then
  echo "refuse: ${VERSION} is not greater than current ${current_name}" >&2
  exit 1
fi

# Tag must not already exist locally or on any code remote.
if git rev-parse -q --verify "refs/tags/${TAG}" >/dev/null; then
  echo "refuse: tag ${TAG} already exists locally" >&2
  exit 1
fi
for remote in $CODE_REMOTES; do
  if git ls-remote --exit-code --tags "$remote" "refs/tags/${TAG}" >/dev/null 2>&1; then
    echo "refuse: tag ${TAG} already exists on ${remote}" >&2
    exit 1
  fi
done
if [[ -n "$GH_RELEASE_REPO" && "$GH_RELEASE_REPO" != "owner/repo" ]]; then
  if gh release view "$TAG" --repo "$GH_RELEASE_REPO" >/dev/null 2>&1; then
    echo "refuse: GitHub release ${TAG} already exists on ${GH_RELEASE_REPO}" >&2
    exit 1
  fi
fi

bump_versions() {
  python3 - "$VERSION_FILE" "$1" "$2" <<'PY'
import re, sys, pathlib
path = pathlib.Path(sys.argv[1])
new_name, new_code = sys.argv[2], sys.argv[3]
text = path.read_text()
m = re.search(r'defaultConfig\s*\{', text)
if not m:
    sys.stderr.write(f"no defaultConfig block in {path}\n")
    raise SystemExit(1)
# Rewrite only the first versionCode / versionName after defaultConfig so
# signingConfigs / other modules in a concatenated file stay untouched.
head, tail = text[:m.start()], text[m.start():]
n_tail, n_code = re.subn(r'(versionCode\s*=\s*)\d+', r'\g<1>' + new_code, tail, count=1)
n_tail2, n_name = re.subn(r'(versionName\s*=\s*")[^"]+"', r'\g<1>' + new_name + '"', n_tail, count=1)
if n_code != 1 or n_name != 1:
    sys.stderr.write(f"failed to bump versions in {path} (code={n_code} name={n_name})\n")
    raise SystemExit(1)
path.write_text(head + n_tail2)
print(f"bumped {path}: versionCode={new_code} versionName={new_name}")
PY
}

echo "Current ${current_name} (code ${current_code}) → ${VERSION} (code ${NEW_CODE})"
bump_versions "$VERSION" "$NEW_CODE"

# Revert the bump on any later failure during a dry-run, and at the end of
# a successful dry-run. Real releases keep the bump so a failed build can
# be retried at the same version (matching existing release skills).
revert_bump() {
  git checkout -- "$VERSION_FILE"
}
if [[ "$DRY" -eq 1 ]]; then
  trap revert_bump EXIT
fi

# --- tests ---
MARKER_DIR="$ROOT/build"
MARKER="$MARKER_DIR/release-test-pass"
tree_hash() {
  # Tracked files only, including unstaged edits (the version bump).
  # Never hashes untracked files, so a local keystore cannot enter the marker.
  (git rev-parse HEAD; git diff HEAD -- "$VERSION_FILE") | sha256sum | awk '{print $1}'
}
TREE_HASH=$(tree_hash)

skip_tests=0
if [[ "$FORCE_TESTS" -eq 0 && -f "$MARKER" ]]; then
  marked=$(awk '/^tree /{print $2}' "$MARKER" | tail -1)
  if [[ "$marked" == "$TREE_HASH" ]]; then
    skip_tests=1
  fi
fi

run_gradle() {
  # shellcheck disable=SC2086
  ./gradlew "$@"
}

if [[ "$skip_tests" -eq 1 ]]; then
  echo "Tests skipped (pass marker matches tree ${TREE_HASH:0:12}…)"
else
  echo "Running tests: ${GRADLE_TEST_TASKS}"
  # shellcheck disable=SC2086
  run_gradle $GRADLE_TEST_TASKS
  mkdir -p "$MARKER_DIR"
  {
    echo "passed $(date -u +%Y-%m-%dT%H:%M:%SZ)"
    echo "tree $TREE_HASH"
    echo "tasks $GRADLE_TEST_TASKS"
  } >"$MARKER"
  echo "Recorded pass marker $MARKER"
fi

# --- build ---
echo "Building: ${GRADLE_BUILD_TASKS}"
# shellcheck disable=SC2086
run_gradle $GRADLE_BUILD_TASKS

missing=0
for spec in $APK_PATHS $AAB_PATHS; do
  p=$(artifact_path "$spec")
  if [[ ! -f "$p" ]]; then
    echo "missing artifact: $p" >&2
    missing=1
  else
    echo "  $(ls -lh "$p")  →  $(artifact_name "$spec")"
  fi
done
if [[ "$missing" -ne 0 ]]; then
  exit 1
fi

# --- signing certificate ---
sha1_of_apk() {
  local apk=$1
  if [[ -z "$APKSIGNER" ]]; then
    echo "apksigner not found under $ANDROID_HOME/build-tools" >&2
    return 1
  fi
  # "Signer #1 certificate SHA-1 digest: ab:cd:..."
  "$APKSIGNER" verify --print-certs "$apk" 2>/dev/null \
    | awk -F': ' 'tolower($0) ~ /sha-1 digest/ {print tolower($2); exit}' \
    | tr -d ' :'
}

verify_cert() {
  local built=$1
  if [[ ! -f "$built" ]]; then
    return 0
  fi
  local built_sha prev_sha tmp tag apk_name
  built_sha=$(sha1_of_apk "$built")
  if [[ -z "$built_sha" ]]; then
    echo "refuse: could not read SHA-1 from $built (unsigned?)" >&2
    return 1
  fi
  echo "Built APK SHA-1: ${built_sha}"
  if [[ -z "$GH_RELEASE_REPO" || "$GH_RELEASE_REPO" == "owner/repo" ]]; then
    echo "No GitHub releases repo configured; skipped previous-release cert check."
    return 0
  fi
  if ! command -v gh >/dev/null 2>&1; then
    echo "warn: gh not on PATH; skipped previous-release cert check." >&2
    return 0
  fi
  tag=$(gh release list --repo "$GH_RELEASE_REPO" --limit 1 --json tagName --jq '.[0].tagName' 2>/dev/null || true)
  if [[ -z "$tag" || "$tag" == "null" ]]; then
    echo "No previous GitHub release on ${GH_RELEASE_REPO}; skipped cert check."
    return 0
  fi
  apk_name=$(gh release view "$tag" --repo "$GH_RELEASE_REPO" --json assets --jq '[.assets[].name | select(test("(?i)\\.apk$"))][0]' 2>/dev/null || true)
  if [[ -z "$apk_name" || "$apk_name" == "null" ]]; then
    echo "Previous release ${tag} has no APK asset; skipped cert check."
    return 0
  fi
  tmp=$(mktemp -d)
  # shellcheck disable=SC2064
  trap "rm -rf '$tmp'" RETURN
  if ! gh release download "$tag" --repo "$GH_RELEASE_REPO" --pattern "$apk_name" --dir "$tmp" >/dev/null; then
    echo "warn: could not download ${apk_name} from ${tag}; skipped cert check." >&2
    return 0
  fi
  prev_sha=$(sha1_of_apk "$tmp/$apk_name")
  if [[ -z "$prev_sha" ]]; then
    echo "warn: could not read SHA-1 from previous APK ${apk_name}; skipped cert check." >&2
    return 0
  fi
  echo "Previous ${tag} SHA-1: ${prev_sha}"
  if [[ "$built_sha" != "$prev_sha" ]]; then
    echo "refuse: signing certificate SHA-1 does not match previous release ${tag}" >&2
    echo "  built:    ${built_sha}" >&2
    echo "  previous: ${prev_sha}" >&2
    return 1
  fi
  echo "Signing certificate matches ${tag}."
}

# Check the first APK (the primary artifact). Additional APKs (e.g. smshelper)
# are listed in APK_PATHS and verified to exist, but are not always signed
# with the same history; we still verify each one that exists.
for spec in $APK_PATHS; do
  verify_cert "$(artifact_path "$spec")"
  break
done

echo
echo "Would release ${APP_NAME} ${TAG} (code ${NEW_CODE}) to:"
echo "  code remotes: ${CODE_REMOTES}"
[[ -n "$GH_RELEASE_REPO" && "$GH_RELEASE_REPO" != "owner/repo" ]] && echo "  GitHub release: ${GH_RELEASE_REPO}"
[[ -n "$PLAY_CMD" ]] && echo "  Play: ${PLAY_CMD}"
if [[ "$NEVER_PUSH_CODE_TO_GITHUB" -eq 1 ]]; then
  echo "  (code is never pushed to GitHub)"
fi

if [[ "$DRY" -eq 1 ]]; then
  echo
  echo "Dry run complete. Version bump will be reverted. Artifacts left in app/build/."
  exit 0
fi

# --- commit, tag, push, publish ---
git add -- "$VERSION_FILE"
git commit -m "Release ${TAG} (build ${NEW_CODE})"

if [[ "$PUSH_TAGS_TO_CODE_REMOTES" -eq 1 ]]; then
  git tag -a "$TAG" -m "Release ${TAG} (build ${NEW_CODE})"
fi

for remote in $CODE_REMOTES; do
  echo "Pushing ${MAIN_BRANCH} to ${remote}…"
  git push "$remote" "$MAIN_BRANCH"
  if [[ "$PUSH_TAGS_TO_CODE_REMOTES" -eq 1 ]]; then
    git push "$remote" "$TAG"
  fi
done

if [[ -n "$GH_RELEASE_REPO" && "$GH_RELEASE_REPO" != "owner/repo" ]]; then
  notes_args=(--title "${APP_NAME} ${TAG}" --notes "Build ${NEW_CODE}")
  if [[ -n "$NOTES" ]]; then
    notes_args=(--title "${APP_NAME} ${TAG}" --notes-file "$NOTES")
  fi
  # Do not pass --verify-tag when the tag lives on a different git host
  # than the GitHub releases repo (calc-vault, cash, mychef, trivia, SOSBlocker).
  asset_dir=$(mktemp -d)
  assets=()
  for spec in $APK_PATHS $AAB_PATHS; do
    p=$(artifact_path "$spec")
    [[ -f "$p" ]] || continue
    dest="$asset_dir/$(artifact_name "$spec")"
    cp "$p" "$dest"
    assets+=("$dest")
  done
  if [[ -n "${MAPPING_FILE:-}" && -f "$MAPPING_FILE" ]]; then
    map_name="mapping-v${VERSION}.txt.gz"
    gzip -9 -c "$MAPPING_FILE" >"$asset_dir/$map_name"
    assets+=("$asset_dir/$map_name")
  fi
  # Upload APKs first (in APK_PATHS order) so a repo whose updater grabs
  # "the first .apk asset" still gets the main app, not a helper.
  gh release create "$TAG" --repo "$GH_RELEASE_REPO" "${notes_args[@]}" "${assets[@]}"
  if [[ "$PRUNE_OLD_RELEASES" -eq 1 ]]; then
    echo "Pruning GitHub releases on ${GH_RELEASE_REPO}, keeping ${PRUNE_KEEP}…"
    gh release list --repo "$GH_RELEASE_REPO" --limit 100 --json tagName --jq '.[].tagName' \
      | tail -n +"$((PRUNE_KEEP + 1))" \
      | xargs -r -I{} gh release delete {} --repo "$GH_RELEASE_REPO" --yes --cleanup-tag \
      || echo "warn: prune skipped or failed (safe to run later by hand)"
  fi
fi

if [[ -n "$PLAY_CMD" ]]; then
  echo "Uploading to Play…"
  # shellcheck disable=SC2086
  eval "$PLAY_CMD"
fi

echo
echo "Released ${APP_NAME} ${TAG} (build ${NEW_CODE})"
for spec in $APK_PATHS $AAB_PATHS; do
  p=$(artifact_path "$spec")
  [[ -f "$p" ]] && echo "  $p  ($(artifact_name "$spec"))  $(wc -c <"$p") bytes"
done
[[ -n "$GH_RELEASE_REPO" && "$GH_RELEASE_REPO" != "owner/repo" ]] && \
  echo "  https://github.com/${GH_RELEASE_REPO}/releases/tag/${TAG}"
