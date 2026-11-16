# Releasing Anova

One-command release (run from the repo root, on `main`, with a clean worktree):

```sh
scripts/release.sh                 # auto-increment the patch version
scripts/release.sh 1.2.3 --notes notes.md
scripts/release.sh --dry-run       # build and verify, do not commit/tag/push/publish
scripts/release.sh --force-tests   # ignore the pass marker and re-run tests
```

A real release requires `--notes <file>`. `--dry-run` does everything up to,
but not including, the commit, tag, push, GitHub release, and Play upload,
then reverts the version bump. Artifacts are left in `app/build/` for inspection.

## What the script does

1. Refuses a dirty tree or a branch other than `main`.
2. `git pull --ff-only`.
3. Bumps `versionCode` by 1 and `versionName` (last component + 1, or the version you passed)
   in `app/build.gradle.kts`.
4. Runs `testDebugUnitTest` (skipped when a pass marker in `build/release-test-pass`
   matches this tree, unless `--force-tests`).
5. Runs `assembleRelease`.
6. Verifies the signing-certificate SHA-1 against the previous GitHub release APK,
   when a releases repo is configured and a previous APK is available.
7. Commits `Release vX.Y.Z (build N)`, tags, and publishes.

`--allow-branch <name>` is only for testing the script on a feature branch.

## Remotes and publishing

- **Code:** `origin` (Bitbucket) and `github`.
- **Publish:** GitHub release on itsikh/Anova with Anova-vVERSION.apk (and mapping if present).

Code goes to Bitbucket (`origin`) and GitHub (`github`). The GitHub release is published on itsikh/Anova.

## Build speed

Heap, worker count, and the Kotlin daemon are **not** hard-coded in this repo.
`~/bin/gradle-tune` writes them to `~/.gradle/gradle.properties` from `nproc`
and available RAM, and a systemd oneshot re-runs it at boot so a VM resize
(4 cores → 8 cores, or a memory change) is picked up automatically.

Do not add `org.gradle.jvmargs`, `org.gradle.workers.max`, or
`kotlin.daemon.jvmargs` to this project's `gradle.properties` — they would
override the tuner. Parallel builds, the build cache, and Kotlin incremental
compilation stay enabled here because they are safe on every machine.

## Secrets

Never commit keystores, `keystore.properties`, `local.properties`, Play keys,
tokens, or `*.bak`. Signing files stay on the machine (or are gitignored).
