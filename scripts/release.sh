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
# --allow-branch is only for testing the script on a feature branch, and only
# together with --dry-run. Real releases must be on the app's main branch.
#
# --first-release allows a build whose signing certificate cannot be compared
# with a previous release (no previous release and no CERT_SHA256 pin).
#
# Real releases are staged: the GitHub release is created as a draft, the
# commit and tag are pushed atomically to each code remote, and only then is
# the draft published. On a failure the script prints how to recover.
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
: "${MAPPING_FILE:=}"
: "${PLAY_ARTIFACTS:=}"   # files PLAY_CMD uploads; deleted before and checked after the build
: "${CERT_SHA256:=}"      # optional pinned SHA-256 of the primary APK signing cert
: "${EXTRA_ASSETS_CMD:=}" # optional: writes extra release assets (e.g. latest.json) into $ASSET_DIR

usage() {
  sed -n '2,28p' "$0" >&2
  exit 2
}

VERSION=""
NOTES=""
DRY=0
FORCE_TESTS=0
FIRST_RELEASE=0
ALLOW_BRANCH=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY=1; shift ;;
    --force-tests) FORCE_TESTS=1; shift ;;
    --first-release) FIRST_RELEASE=1; shift ;;
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
if [[ -n "$ALLOW_BRANCH" && "$DRY" -eq 0 ]]; then
  echo "refuse: --allow-branch requires --dry-run" >&2
  exit 2
fi

# One release.sh run per worktree at a time (a dry-run's revert must never
# undo a real release's bump).
LOCK="$(git rev-parse --git-dir)/release.lock"
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "refuse: another release.sh is running in this worktree ($LOCK)" >&2
  exit 1
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

# --- secrets must never be tracked ---
SECRET_RE='(\.(jks|keystore|p12|pfx|pem|bak)$)|(^|/)keystore\.properties$|(^|/)local\.properties$|(^|/)oauth\.json$|(^|/)\.keys/|play[^/]*key[^/]*\.json$|(^|/)\.env$'
tracked_secrets=$(git ls-files | grep -E "$SECRET_RE" || true)
inline_pw=$(git grep -nIE '(store|key)Password[[:space:]]*(=|\()[[:space:]]*"[^"$]+"' -- '*.gradle' '*.gradle.kts' 2>/dev/null || true)
[[ -n "$inline_pw" ]] && tracked_secrets="${tracked_secrets:+$tracked_secrets
}${inline_pw}"
if [[ -n "$tracked_secrets" ]]; then
  echo "SECRETS ARE TRACKED IN GIT:" >&2
  echo "$tracked_secrets" | sed 's/^/  /' >&2
  if [[ "$DRY" -eq 0 ]]; then
    echo "refuse: untrack and purge these (and rotate any key that was pushed) before releasing" >&2
    exit 1
  fi
  echo "warn: a real release would refuse here" >&2
fi

# --- never push code to GitHub when the app says so ---
if [[ "$NEVER_PUSH_CODE_TO_GITHUB" -eq 1 ]]; then
  for remote in $CODE_REMOTES; do
    urls=$(git remote get-url --push --all "$remote" 2>/dev/null || true)
    if [[ -z "$urls" ]] || grep -qi 'github\.com' <<<"$urls"; then
      echo "refuse: NEVER_PUSH_CODE_TO_GITHUB=1 but code remote '${remote}' pushes to: ${urls:-<missing>}" >&2
      exit 1
    fi
  done
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
HAS_GH=0
[[ -n "$GH_RELEASE_REPO" && "$GH_RELEASE_REPO" != "owner/repo" ]] && HAS_GH=1
for remote in $CODE_REMOTES; do
  set +e
  git ls-remote --exit-code --tags "$remote" "refs/tags/${TAG}" >/dev/null 2>&1
  rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    echo "refuse: tag ${TAG} already exists on ${remote}" >&2
    exit 1
  elif [[ $rc -ne 2 ]]; then
    echo "refuse: cannot reach code remote ${remote} (git ls-remote exit ${rc})" >&2
    exit 1
  fi
done
if [[ "$HAS_GH" -eq 1 ]]; then
  if ! command -v gh >/dev/null 2>&1 || ! gh auth status >/dev/null 2>&1; then
    echo "refuse: gh is missing or not logged in; cannot check or publish ${GH_RELEASE_REPO}" >&2
    exit 1
  fi
  set +e
  ghout=$(gh release view "$TAG" --repo "$GH_RELEASE_REPO" --json tagName 2>&1)
  rc=$?
  set -e
  if [[ $rc -eq 0 ]]; then
    echo "refuse: GitHub release ${TAG} already exists on ${GH_RELEASE_REPO}" >&2
    exit 1
  elif ! grep -qi 'not found' <<<"$ghout"; then
    echo "refuse: cannot check GitHub release ${TAG} on ${GH_RELEASE_REPO}: ${ghout}" >&2
    exit 1
  fi
fi
if [[ "$DRY" -eq 0 ]]; then
  for remote in $CODE_REMOTES; do
    if ! git push --dry-run "$remote" "$MAIN_BRANCH" >/dev/null 2>&1; then
      echo "refuse: git push --dry-run ${remote} ${MAIN_BRANCH} failed (auth, network, or non-fast-forward)" >&2
      exit 1
    fi
  done
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

# Revert the bump on every exit before the release commit (dry-run or a
# failed real release), including Ctrl-C and kill, so a rerun starts clean.
revert_bump() {
  git checkout -- "$VERSION_FILE" 2>/dev/null || true
}
trap revert_bump EXIT
trap 'exit 130' INT TERM HUP

echo "Current ${current_name} (code ${current_code}) → ${VERSION} (code ${NEW_CODE})"
bump_versions "$VERSION" "$NEW_CODE"

# --- tests ---
MARKER_DIR="$ROOT/build"
MARKER="$MARKER_DIR/release-test-pass"
tree_hash() {
  # Tracked files only, including unstaged edits (the version bump).
  # Never hashes untracked files, so a local keystore cannot enter the marker.
  (git rev-parse HEAD; git diff HEAD -- "$VERSION_FILE"; echo "tasks $GRADLE_TEST_TASKS") | sha256sum | awk '{print $1}'
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
# Delete old outputs first so a stale signed APK/AAB from an earlier build can
# never be published under the new version.
for spec in $APK_PATHS $AAB_PATHS; do rm -f "$(artifact_path "$spec")"; done
for f in $PLAY_ARTIFACTS; do rm -f "$f"; done
[[ -n "$MAPPING_FILE" ]] && rm -f "$MAPPING_FILE"
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
for f in $PLAY_ARTIFACTS; do
  [[ -f "$f" ]] || { echo "missing Play artifact: $f" >&2; missing=1; }
done
if [[ "$missing" -ne 0 ]]; then
  exit 1
fi

AAPT2=""
for a in "$ANDROID_HOME"/build-tools/*/aapt2; do
  [[ -x "$a" ]] && AAPT2="$a"
done
# The primary APK must carry the new versionCode.
primary_apk=""
for spec in $APK_PATHS; do primary_apk=$(artifact_path "$spec"); break; done
if [[ -n "$primary_apk" ]]; then
  [[ -n "$AAPT2" ]] || { echo "refuse: aapt2 not found under $ANDROID_HOME/build-tools" >&2; exit 1; }
  badging=$("$AAPT2" dump badging "$primary_apk" 2>&1) || true
  built_code=$(sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging" | head -1)
  [[ -n "$built_code" ]] || { echo "refuse: could not read versionCode from ${primary_apk} (aapt2: $(head -2 <<<"$badging"))" >&2; exit 1; }
  if [[ "$built_code" != "$NEW_CODE" ]]; then
    echo "refuse: ${primary_apk} has versionCode '${built_code}', expected ${NEW_CODE}" >&2
    exit 1
  fi
  echo "Built APK versionCode ${built_code}"
fi

# --- signing certificate ---
# SHA-256 of the signing certificate, lowercase hex without separators.
cert_of_apk() {
  local apk=$1
  [[ -n "$APKSIGNER" ]] || { echo "apksigner not found under $ANDROID_HOME/build-tools" >&2; return 1; }
  "$APKSIGNER" verify --print-certs "$apk" 2>/dev/null \
    | awk -F': ' 'tolower($0) ~ /certificate sha-256 digest/ {print tolower($2); exit}' \
    | tr -d ' :'
}
cert_fail() {
  if [[ "$FIRST_RELEASE" -eq 1 && -z "$PREV_TAG" ]]; then
    echo "warn: $* (allowed by --first-release)" >&2
    return 0
  fi
  echo "refuse: $* (pass --first-release only if this really is the first signed release)" >&2
  return 1
}

PREV_TAG=""
PREV_DIR=""
if [[ "$HAS_GH" -eq 1 ]]; then
  PREV_TAG=$(gh release list --repo "$GH_RELEASE_REPO" --limit 20 --exclude-drafts --exclude-pre-releases \
    --json tagName --jq '.[0].tagName // empty')
  if [[ -n "$PREV_TAG" ]]; then
    PREV_DIR=$(mktemp -d)
    if ! gh release download "$PREV_TAG" --repo "$GH_RELEASE_REPO" --pattern '*.apk' --dir "$PREV_DIR" >/dev/null; then
      echo "refuse: could not download the APKs of previous release ${PREV_TAG} for the signing check" >&2
      exit 1
    fi
  fi
fi

# Every APK is checked. The primary APK (first in APK_PATHS) must match
# CERT_SHA256 when pinned, and the APK of the same asset name in the previous
# release; a check that cannot be made stops the release.
cert_rc=0
first=1
for spec in $APK_PATHS; do
  p=$(artifact_path "$spec"); name=$(artifact_name "$spec")
  built=$(cert_of_apk "$p" || true)
  if [[ -z "$built" ]]; then
    echo "refuse: could not read the signing certificate of $p (unsigned?)" >&2
    cert_rc=1; first=0; continue
  fi
  echo "Built ${name} cert SHA-256: ${built}"
  checked=0
  if [[ "$first" -eq 1 && -n "$CERT_SHA256" ]]; then
    pin=$(tr -d ' :' <<<"$CERT_SHA256" | tr 'A-F' 'a-f')
    if [[ "$built" != "$pin" ]]; then
      echo "refuse: ${name} certificate does not match CERT_SHA256 in release.conf" >&2
      cert_rc=1
    fi
    checked=1
  fi
  prev_file=""
  if [[ -n "$PREV_DIR" ]]; then
    # Match the previous asset by name; a {version} in the asset name matches
    # any version (e.g. MyApp-{version}.apk).
    pat=$(basename "$p")
    [[ "$spec" == *"="* ]] && { pat=${spec#*=}; pat=${pat//\{version\}/*}; }
    for f in "$PREV_DIR"/$pat; do [[ -f "$f" ]] && { prev_file=$f; break; }; done
  fi
  if [[ -n "$prev_file" ]]; then
    prev=$(cert_of_apk "$prev_file" || true)
    if [[ -z "$prev" ]]; then
      cert_fail "could not read the certificate of ${name} from ${PREV_TAG}" || cert_rc=1
    elif [[ "$built" != "$prev" ]]; then
      echo "refuse: ${name} certificate does not match ${PREV_TAG} (previous ${prev})" >&2
      cert_rc=1
    else
      echo "  matches ${PREV_TAG}"
    fi
    checked=1
  fi
  if [[ "$checked" -eq 0 ]]; then
    if [[ "$first" -eq 1 ]]; then
      cert_fail "no previous release APK named ${name} and no CERT_SHA256 pin; cannot verify the signing key" || cert_rc=1
    else
      if [[ -n "$PREV_TAG" ]]; then
        echo "refuse: ${PREV_TAG} has no asset matching ${name}; cannot verify its signing key" >&2
        cert_rc=1
      else
        echo "warn: ${name} has no previous release asset to compare with" >&2
      fi
    fi
  fi
  first=0
done
[[ -n "$PREV_DIR" ]] && rm -rf "$PREV_DIR"
[[ "$cert_rc" -eq 0 ]] || exit 1

# --- release assets (built in dry-run too, so the hook is exercised) ---
asset_dir=$(mktemp -d)
assets=()
for spec in $APK_PATHS $AAB_PATHS; do
  p=$(artifact_path "$spec")
  dest="$asset_dir/$(artifact_name "$spec")"
  cp "$p" "$dest"
  assets+=("$dest")
done
if [[ -n "$MAPPING_FILE" && -f "$MAPPING_FILE" ]]; then
  map_name="mapping-${TAG}.txt.gz"
  gzip -9 -c "$MAPPING_FILE" >"$asset_dir/$map_name"
  assets+=("$asset_dir/$map_name")
fi
if [[ -n "$EXTRA_ASSETS_CMD" ]]; then
  extra_dir=$(mktemp -d)
  echo "Extra assets: ${EXTRA_ASSETS_CMD}"
  ASSET_DIR="$extra_dir" VERSION="$VERSION" NEW_CODE="$NEW_CODE" TAG="$TAG" \
    GH_RELEASE_REPO="$GH_RELEASE_REPO" NOTES="$NOTES" bash -c "$EXTRA_ASSETS_CMD"
  shopt -s nullglob
  extra=("$extra_dir"/*)
  shopt -u nullglob
  [[ ${#extra[@]} -gt 0 ]] || { echo "refuse: EXTRA_ASSETS_CMD wrote no files" >&2; exit 1; }
  for f in "${extra[@]}"; do
    if [[ "$f" == *.json ]] && ! python3 -m json.tool "$f" >/dev/null; then
      echo "refuse: extra asset $(basename "$f") is not valid JSON" >&2; exit 1
    fi
    echo "  $(basename "$f"): $(head -c 400 "$f" | tr '\n' ' ')"
    mv "$f" "$asset_dir/"
    assets+=("$asset_dir/$(basename "$f")")
  done
  rmdir "$extra_dir"
fi

echo
echo "Would release ${APP_NAME} ${TAG} (code ${NEW_CODE}) to:"
echo "  code remotes: ${CODE_REMOTES}"
if [[ "$HAS_GH" -eq 1 ]]; then
  echo "  GitHub release: ${GH_RELEASE_REPO}"
  for f in "${assets[@]}"; do echo "    asset: $(basename "$f")"; done
fi
if [[ -n "$PLAY_CMD" ]]; then echo "  Play: ${PLAY_CMD}"; fi
if [[ "$NEVER_PUSH_CODE_TO_GITHUB" -eq 1 ]]; then
  echo "  (code is never pushed to GitHub)"
fi

if [[ "$DRY" -eq 1 ]]; then
  echo
  echo "Dry run complete. Version bump will be reverted. Artifacts left in app/build/."
  rm -rf "$asset_dir"
  exit 0
fi

# --- commit, tag, push, publish ---
# Order: draft GitHub release → commit + tag → atomic push to each remote →
# publish the draft → prune → Play. A failure prints the recovery steps.
STAGE="before commit"
PUSHED=""
recover() {
  local rc=$?
  [[ $rc -eq 0 || "$STAGE" == "done" ]] && return 0
  local del_draft="" publish="" push_cmd
  if [[ "$HAS_GH" -eq 1 ]]; then
    del_draft="gh release delete ${TAG} --repo ${GH_RELEASE_REPO} --yes"
    publish="gh release edit ${TAG} --repo ${GH_RELEASE_REPO} --draft=false"
  fi
  if [[ "$PUSH_TAGS_TO_CODE_REMOTES" -eq 1 ]]; then
    push_cmd="git push --atomic <remote> ${MAIN_BRANCH} ${TAG}"
  else
    push_cmd="git push <remote> ${MAIN_BRANCH}"
  fi
  echo >&2
  echo "RELEASE ${TAG} FAILED at stage: ${STAGE} (exit ${rc})" >&2
  case "$STAGE" in
    "before commit")
      echo "  Nothing was committed or pushed. The version bump was reverted." >&2 ;;
    "creating draft"|"draft created")
      echo "  Nothing was committed. The version bump was reverted." >&2
      [[ -n "$del_draft" ]] && echo "  Delete any draft left behind: ${del_draft}" >&2 ;;
    "committing")
      echo "  Nothing was pushed. If HEAD is the 'Release ${TAG}' commit: git tag -d ${TAG} 2>/dev/null; git reset --hard HEAD~1" >&2
      [[ -n "$del_draft" ]] && echo "  Then delete the draft: ${del_draft}" >&2 ;;
    "pushing")
      echo "  Pushed so far: ${PUSHED:-none}." >&2
      if [[ -z "$PUSHED" ]]; then
        echo "  Nothing was pushed: git tag -d ${TAG}; git reset --hard HEAD~1" >&2
        [[ -n "$del_draft" ]] && echo "  and delete the draft: ${del_draft}" >&2
      else
        echo "  Push the rest by hand: ${push_cmd}" >&2
        [[ -n "$publish" ]] && echo "  then publish: ${publish}" >&2
      fi ;;
    "pushed")
      echo "  Code is pushed to every remote." >&2
      [[ -n "$publish" ]] && echo "  Publish the draft: ${publish}" >&2 ;;
    "published")
      echo "  The release is out. Only a later step failed (prune or Play); rerun it by hand${PLAY_CMD:+: ${PLAY_CMD}}." >&2 ;;
  esac
}
revert_bump_if_uncommitted() {
  case "$STAGE" in "before commit"|"creating draft"|"draft created") revert_bump ;; esac
}
trap 'recover; revert_bump_if_uncommitted' EXIT

if [[ "$HAS_GH" -eq 1 ]]; then
  notes_args=(--title "${APP_NAME} ${TAG}" --notes "Build ${NEW_CODE}")
  if [[ -n "$NOTES" ]]; then
    notes_args=(--title "${APP_NAME} ${TAG}" --notes-file "$NOTES")
  fi
  # No --verify-tag: the tag may live on a different git host than the
  # releases repo (calc-vault, cash, mychef, trivia, SOSBlocker).
  # APKs are uploaded first (APK_PATHS order) so an updater that grabs
  # "the first .apk asset" gets the main app, not a helper.
  STAGE="creating draft"
  gh release create "$TAG" --repo "$GH_RELEASE_REPO" --draft "${notes_args[@]}" "${assets[@]}"
  STAGE="draft created"
fi

STAGE="committing"
git add -- "$VERSION_FILE"
git commit -m "Release ${TAG} (build ${NEW_CODE})"
# Always tag locally; PUSH_TAGS_TO_CODE_REMOTES only controls pushing it.
git tag -a "$TAG" -m "Release ${TAG} (build ${NEW_CODE})"

STAGE="pushing"
for remote in $CODE_REMOTES; do
  echo "Pushing ${MAIN_BRANCH} to ${remote}…"
  if [[ "$PUSH_TAGS_TO_CODE_REMOTES" -eq 1 ]]; then
    git push --atomic "$remote" "$MAIN_BRANCH" "$TAG"
  else
    git push "$remote" "$MAIN_BRANCH"
  fi
  PUSHED="${PUSHED:+$PUSHED }$remote"
done
STAGE="pushed"

if [[ "$HAS_GH" -eq 1 ]]; then
  gh release edit "$TAG" --repo "$GH_RELEASE_REPO" --draft=false
fi
STAGE="published"

if [[ "$HAS_GH" -eq 1 && "$PRUNE_OLD_RELEASES" -eq 1 ]]; then
  echo "Pruning GitHub releases on ${GH_RELEASE_REPO}, keeping ${PRUNE_KEEP}…"
  tags=$(gh release list --repo "$GH_RELEASE_REPO" --limit 100 --exclude-drafts --exclude-pre-releases \
    --json tagName --jq '.[].tagName' || true)
  if [[ "$(head -1 <<<"$tags")" != "$TAG" ]]; then
    echo "warn: newest release is not ${TAG}; prune skipped (run it by hand later)" >&2
  else
    tail -n +"$((PRUNE_KEEP + 1))" <<<"$tags" \
      | xargs -r -I{} gh release delete {} --repo "$GH_RELEASE_REPO" --yes \
      || echo "warn: prune failed part-way (safe to run later by hand)" >&2
  fi
fi

if [[ -n "$PLAY_CMD" ]]; then
  echo "Uploading to Play…"
  # shellcheck disable=SC2086
  eval "$PLAY_CMD"
fi
STAGE="done"
rm -rf "$asset_dir"

echo
echo "Released ${APP_NAME} ${TAG} (build ${NEW_CODE})"
for spec in $APK_PATHS $AAB_PATHS; do
  p=$(artifact_path "$spec")
  if [[ -f "$p" ]]; then echo "  $p  ($(artifact_name "$spec"))  $(wc -c <"$p") bytes"; fi
done
if [[ "$HAS_GH" -eq 1 ]]; then
  echo "  https://github.com/${GH_RELEASE_REPO}/releases/tag/${TAG}"
fi
exit 0
