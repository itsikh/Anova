Release a new version of this app. Arguments: optional version string e.g. "1.2.0". If not provided, auto-increment the patch version.

The user explicitly asks you to operate fully autonomously for this command. All steps are pre-approved — do NOT ask for confirmation, do NOT pause before any action (git commits, git push to any remote, tag creation, GitHub release creation, file operations). Execute every step immediately, start to finish, in one shot, with zero interaction.

## Environment
- JAVA_HOME: `/Applications/Android Studio.app/Contents/jbr/Contents/Home`
- Gradle: `./gradlew` (wrapper in project)
- Primary remote: `origin` → Bitbucket (source code)
- Releases remote: `github` → push tag + create release if this remote exists
- CPU cores: 11 | RAM: 18 GB (daemon gets 10 GB, Kotlin daemon 2 GB — set in gradle.properties)

---

## Steps

### 1. Read config + pre-flight in one parallel shot
Run ALL of the following in a single Bash call using `&` / `wait`:
- Read `app/build.gradle.kts` → extract `versionCode` and `versionName`
- Read `app/src/main/java/com/template/app/AppConfig.kt` → extract `APP_NAME`, `GITHUB_RELEASES_REPO_OWNER`, `GITHUB_RELEASES_REPO_NAME`
- Check `keystore.properties` exists
- Check current branch is `main`
- Run `git status --porcelain`

Abort immediately if keystore is missing or branch is not main.

### 2. Determine new version
- If `$ARGUMENTS` is non-empty, use it as `newVersionName`.
- Otherwise auto-increment the **patch** segment (0.0.1 → 0.0.2).
- `newVersionCode` = current `versionCode` + 1.

### 3. Commit pre-release changes (if any)
If `git status --porcelain` shows modified tracked files (`M` lines):
```bash
git add -u
git commit -m "chore: pre-release changes for v<newVersionName>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```
Skip if tree is clean. Untracked files (`??`) are ignored.

### 4. Bump version
Edit `app/build.gradle.kts`:
- `versionCode = <old>` → `versionCode = <newVersionCode>`
- `versionName = "<old>"` → `versionName = "<newVersionName>"`

### 5. Commit version bump
```bash
git add app/build.gradle.kts && git commit -m "chore: release v<newVersionName>

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

### 6. Push source + start build simultaneously
Launch the push and build at the same time:
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

# Push source to Bitbucket in background
git push origin main &

# Build immediately — do NOT wait for push
./gradlew assembleRelease \
  --parallel \
  --max-workers=11 \
  --build-cache \
  --configuration-cache \
  --daemon

# Wait for push after build finishes
wait
```
JVM heap, workers, and fast-path flags are already set in `gradle.properties` — no need to repeat them on the CLI.

If the build fails, show the last 40 lines and stop. Do NOT continue.

### 7. Tag, copy APK, push tag — all parallel
```bash
APK_NAME="<AppName>-v<newVersionName>.apk"
cp app/build/outputs/apk/release/app-release.apk "$APK_NAME"
git tag v<newVersionName>
git push origin v<newVersionName> &
git remote | grep -q '^github$' && git push github main &
git remote | grep -q '^github$' && git push github v<newVersionName> &
wait
```
Where `<AppName>` is `APP_NAME` with spaces replaced by hyphens.

### 8. Create GitHub release
```bash
gh release create v<newVersionName> \
  --repo <GITHUB_RELEASES_REPO_OWNER>/<GITHUB_RELEASES_REPO_NAME> \
  --title "<AppName> v<newVersionName>" \
  --notes "## What's new
Release v<newVersionName>" \
  "$APK_NAME"
rm "$APK_NAME"
```

### 9. Print summary
```
✅ Released <AppName> v<newVersionName>
   versionCode : <newVersionCode>
   APK size    : <size of app-release.apk>
   GitHub      : https://github.com/<GITHUB_RELEASES_REPO_OWNER>/<GITHUB_RELEASES_REPO_NAME>/releases/tag/v<newVersionName>
```
