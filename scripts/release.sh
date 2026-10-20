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

# ---- 国内镜像通道：dist 分支（latest.json + APK），经 jsDelivr CDN 分发 ----
# GitHub 的安装包下载在国内经常连不上，应用会自动改走这里。
echo "→ 更新 dist 分支（jsDelivr 镜像）"
WT=build/dist-branch
rm -rf "$WT"; git worktree prune
if git ls-remote --exit-code --heads origin dist >/dev/null 2>&1; then
  git fetch -q origin dist
  git worktree add -q -B dist "$WT" origin/dist
else
  git worktree add -q --detach "$WT"
  (cd "$WT" && git checkout -q --orphan dist && git rm -rq . && git clean -fdq)
fi
cp "$APK" "$WT/"
python3 - "$WT" "$TAG" "$VER" "$CODE" "$(basename "$APK")" "$SHA" "$NOTES" <<'PY'
import json, os, sys, glob
wt, tag, ver, code, apk, sha, notes = sys.argv[1:8]
info = {"tag": tag, "versionName": ver, "versionCode": int(code), "apk": apk,
        "size": os.path.getsize(os.path.join(wt, apk)), "sha256": sha,
        "notes": open(notes, encoding="utf-8").read().strip()}
json.dump(info, open(os.path.join(wt, "latest.json"), "w", encoding="utf-8"), ensure_ascii=False, indent=2)
# 只保留最近 3 个安装包，控制分支大小（jsDelivr 对仓库体积有限制）
apks = sorted(glob.glob(os.path.join(wt, "duoplayer-*.apk")), key=os.path.getmtime)
for old in apks[:-3]:
    os.remove(old)
open(os.path.join(wt, "README.md"), "w").write(
    "# dist\n\n自动生成：应用内更新的镜像通道（jsDelivr）。请勿手动修改。\n\n"
    f"最新版本：{tag}\n\n下载：https://cdn.jsdelivr.net/gh/AIcivilization/duoplayer@dist/{apk}\n")
PY
(cd "$WT" && git add -A && git commit -qm "dist $TAG" && git push -q origin dist)
git worktree remove --force "$WT"
# 刷新 jsDelivr 对 latest.json 的缓存
for h in purge.jsdelivr.net; do curl -s -m 20 "https://$h/gh/$REPO@dist/latest.json" >/dev/null || true; done

echo "✓ 已发布 https://github.com/$REPO/releases/tag/$TAG"
echo "  国内镜像：https://cdn.jsdelivr.net/gh/$REPO@dist/$(basename "$APK")"
