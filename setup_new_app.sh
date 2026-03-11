#!/bin/bash
# setup_new_app.sh — Run once when creating a new Android app from this template.
# Asks a few questions and patches all placeholder values automatically.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_SRC="$SCRIPT_DIR/app/src/main/java/com/template/app"

echo ""
echo "========================================="
echo "  Android App Template — Setup Wizard"
echo "========================================="
echo ""

# --- Questions ---

read -r -p "App name (display name, e.g. 'My Cool App'): " APP_NAME
if [[ -z "$APP_NAME" ]]; then
  echo "Error: app name cannot be empty." && exit 1
fi

read -r -p "App ID (e.g. com.mycompany.myapp): " APP_ID
if [[ -z "$APP_ID" ]]; then
  echo "Error: app ID cannot be empty." && exit 1
fi
# Derive a simple identifier from the app ID for filenames (last segment)
APP_IDENTIFIER="${APP_ID##*.}"

echo ""
echo "--- GitHub: Bug Reports / Issues ---"
read -r -p "Issues repo owner (GitHub username or org): " ISSUES_OWNER
if [[ -z "$ISSUES_OWNER" ]]; then
  echo "Error: issues repo owner cannot be empty." && exit 1
fi
read -r -p "Issues repo name (bug reports will be created here): " ISSUES_REPO
if [[ -z "$ISSUES_REPO" ]]; then
  echo "Error: issues repo name cannot be empty." && exit 1
fi

echo ""
echo "--- GitHub: App Updates / Releases ---"
read -r -p "Releases repo owner [default: same as issues owner ($ISSUES_OWNER)]: " RELEASES_OWNER
RELEASES_OWNER="${RELEASES_OWNER:-$ISSUES_OWNER}"
read -r -p "Releases repo name (APK releases published here) [default: same as issues repo ($ISSUES_REPO)]: " RELEASES_REPO
RELEASES_REPO="${RELEASES_REPO:-$ISSUES_REPO}"

echo ""
read -r -p "Version name (e.g. 0.0.1) [default: 0.0.1]: " VERSION_NAME
VERSION_NAME="${VERSION_NAME:-0.0.1}"

echo ""
echo "-----------------------------------------"
echo "  Summary"
echo "-----------------------------------------"
echo "  App Name          : $APP_NAME"
echo "  App ID            : $APP_ID"
echo "  Issues Repo       : $ISSUES_OWNER/$ISSUES_REPO"
echo "  Releases Repo     : $RELEASES_OWNER/$RELEASES_REPO"
echo "  Version           : $VERSION_NAME"
echo "-----------------------------------------"
read -r -p "Looks good? Apply changes? [y/N]: " CONFIRM
if [[ "$CONFIRM" != "y" && "$CONFIRM" != "Y" ]]; then
  echo "Aborted." && exit 0
fi

echo ""
echo "Applying changes..."

# --- Patch AppConfig.kt ---
APPCONFIG="$APP_SRC/AppConfig.kt"
sed -i '' \
  -e "s|const val GITHUB_ISSUES_REPO_OWNER = \"owner\".*|const val GITHUB_ISSUES_REPO_OWNER = \"$ISSUES_OWNER\"|" \
  -e "s|const val GITHUB_ISSUES_REPO_NAME = \"repo\".*|const val GITHUB_ISSUES_REPO_NAME = \"$ISSUES_REPO\"|" \
  -e "s|const val GITHUB_RELEASES_REPO_OWNER = \"owner\".*|const val GITHUB_RELEASES_REPO_OWNER = \"$RELEASES_OWNER\"|" \
  -e "s|const val GITHUB_RELEASES_REPO_NAME = \"repo\".*|const val GITHUB_RELEASES_REPO_NAME = \"$RELEASES_REPO\"|" \
  -e "s|const val APP_NAME = \"TemplateApp\".*|const val APP_NAME = \"$APP_NAME\"|" \
  -e "s|const val SECURE_PREFS_FILENAME = \"template_secure_keys\".*|const val SECURE_PREFS_FILENAME = \"${APP_IDENTIFIER}_secure_keys\"|" \
  "$APPCONFIG"
echo "  [OK] AppConfig.kt"

# --- Patch app/build.gradle.kts ---
BUILD_GRADLE="$SCRIPT_DIR/app/build.gradle.kts"
sed -i '' \
  -e "s|namespace = \"com\.template\.app\"|namespace = \"$APP_ID\"|" \
  -e "s|applicationId = \"com\.template\.app\"|applicationId = \"$APP_ID\"|" \
  -e "s|versionName = \".*\"|versionName = \"$VERSION_NAME\"|" \
  "$BUILD_GRADLE"
echo "  [OK] app/build.gradle.kts"

# --- Patch strings.xml ---
STRINGS_XML="$SCRIPT_DIR/app/src/main/res/values/strings.xml"
sed -i '' \
  -e "s|<string name=\"app_name\">.*</string>|<string name=\"app_name\">$APP_NAME</string>|" \
  "$STRINGS_XML"
echo "  [OK] res/values/strings.xml"

# --- Patch themes.xml ---
THEME_NAME="$(echo "$APP_NAME" | tr -d ' ')"
THEMES_XML="$SCRIPT_DIR/app/src/main/res/values/themes.xml"
sed -i '' \
  -e "s|Theme\.TemplateApp|Theme.$THEME_NAME|g" \
  "$THEMES_XML"
echo "  [OK] res/values/themes.xml"

# --- Patch AndroidManifest.xml ---
MANIFEST="$SCRIPT_DIR/app/src/main/AndroidManifest.xml"
sed -i '' \
  -e "s|android:theme=\"@style/Theme\.TemplateApp\"|android:theme=\"@style/Theme.$THEME_NAME\"|g" \
  "$MANIFEST"
echo "  [OK] AndroidManifest.xml"

# --- Patch settings.gradle.kts (project name) ---
SETTINGS_GRADLE="$SCRIPT_DIR/settings.gradle.kts"
sed -i '' \
  -e "s|rootProject\.name = \".*\"|rootProject.name = \"$APP_NAME\"|" \
  "$SETTINGS_GRADLE"
echo "  [OK] settings.gradle.kts"

echo ""
echo "========================================="
echo "  Done! Next steps:"
echo "  1. Rename the package folder from"
echo "     com/template/app → $(echo "$APP_ID" | tr '.' '/')"
echo "     (Android Studio: Refactor → Rename)"
echo "  2. Replace the app icon in res/mipmap-*"
echo "  3. Add your GitHub PAT in Settings → (Admin Mode) → GitHub Token"
echo "  4. Sync Gradle in Android Studio"
echo "========================================="
