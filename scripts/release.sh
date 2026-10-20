#!/bin/bash
# 发布新版本到 GitHub Releases（应用内「检查更新」读取的就是这里）。
#
# 用法：
#   1. 修改 app/build.gradle.kts 里的 versionCode（+1）和 versionName（如 1.4）
#   2. 写 release-notes/v<versionName>.md（更新说明，会显示在应用的更新提示里）
#   3. 提交代码后运行：scripts/release.sh
set -euo pipefail
cd "$(dirname "$0")/.."

REPO="AIcivilization/duoplayer"
# 发布证书 SHA-256 指纹：所有版本必须一致，否则用户无法覆盖安装
EXPECTED_CERT="8789555f6385fca186d1c6538d8e684ec4622480403e0a3596a554bdbd2229b8"
KEYPROPS="$HOME/.config/duoplayer/keystore.properties"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="${JAVA_HOME:-$HOME/.local/android-toolchain/jdk/Contents/Home}"

die() { echo "✗ $*" >&2; exit 1; }

[ -f "$KEYPROPS" ] || die "找不到发布密钥 $KEYPROPS（没有它就不能发布可自动更新的版本）"
gh auth status >/dev/null 2>&1 || die "gh 未登录：先运行 gh auth login"
[ -z "$(git status --porcelain)" ] || die "工作区有未提交的改动，请先提交"

VER=$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' app/build.gradle.kts)
TAG="v$VER"
NOTES="release-notes/$TAG.md"
[ -n "$VER" ] && [ -n "$CODE" ] || die "读不到版本号"
[ -f "$NOTES" ] || die "缺少更新说明 $NOTES"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "标签 $TAG 已存在：请先提高版本号"

echo "→ 构建 $TAG（versionCode $CODE）"
./gradlew clean testDebugUnitTest assembleRelease --console=plain -q

APK_SRC=app/build/outputs/apk/release/app-release.apk
CERT=$("$SDK/build-tools/34.0.0/apksigner" verify --print-certs "$APK_SRC" | sed -n 's/.*SHA-256 digest: //p' | head -1)
[ "$CERT" = "$EXPECTED_CERT" ] || die "签名证书不对：$CERT"

mkdir -p build/dist
APK="build/dist/duoplayer-$VER.apk"
cp "$APK_SRC" "$APK"
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)

echo "→ 打标签并推送"
git tag -a "$TAG" -m "双轨 Hi-Fi $VER"
git push origin HEAD "$TAG"

echo "→ 创建 GitHub Release"
{ cat "$NOTES"; printf '\n\n---\nAPK SHA-256: `%s`\n签名证书 SHA-256: `%s`\n' "$SHA" "$EXPECTED_CERT"; } > build/dist/notes.md
gh release create "$TAG" "$APK" --repo "$REPO" --title "双轨 Hi-Fi $VER" --notes-file build/dist/notes.md

echo "✓ 已发布 https://github.com/$REPO/releases/tag/$TAG"
