#!/bin/bash
# 把安装包和 latest.json 推到 dist 分支，供 jsDelivr 镜像分发（国内无需访问 GitHub 即可更新）。
# 由 release.sh 调用；也可单独补发：scripts/publish-dist.sh v1.7 1.7 8 build/dist/duoplayer-1.7.apk <sha256> release-notes/v1.7.md
set -euo pipefail
cd "$(dirname "$0")/.."
TAG="$1"; VER="$2"; CODE="$3"; APK="$4"; SHA="$5"; NOTES="$6"
REPO="AIcivilization/duoplayer"
WT=build/dist-branch

echo "→ 更新 dist 分支（jsDelivr 镜像）"
git worktree remove --force "$WT" 2>/dev/null || true
rm -rf "$WT"; git worktree prune
if git ls-remote --exit-code --heads origin dist >/dev/null 2>&1; then
  git fetch -q origin dist
  git worktree add -q -B dist "$WT" origin/dist
else
  git worktree add -q --detach "$WT"
  (cd "$WT" && git checkout -q --orphan dist && git rm -rfq --cached . >/dev/null \
     && find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +)
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
apks = sorted(glob.glob(os.path.join(wt, "duoplayer-*.apk")),
              key=lambda p: [int(x) for x in os.path.basename(p)[10:-4].split(".") if x.isdigit()])
for old in apks[:-3]:
    os.remove(old)
open(os.path.join(wt, "README.md"), "w", encoding="utf-8").write(
    "# dist\n\n自动生成：应用内更新的镜像通道（jsDelivr），请勿手动修改。\n"
    "Auto-generated mirror channel for in-app updates. Do not edit.\n\n"
    f"最新版本 / Latest: {tag}\n\n"
    f"下载 / Download: https://cdn.jsdelivr.net/gh/{os.environ.get('REPO','AIcivilization/duoplayer')}@dist/{apk}\n")
PY
(cd "$WT" && git add -A && git commit -qm "dist $TAG" && git push -q origin dist)
git worktree remove --force "$WT"
# 刷新 jsDelivr 对 latest.json 和目录页的缓存
curl -s -m 20 "https://purge.jsdelivr.net/gh/$REPO@dist/latest.json" >/dev/null || true
curl -s -m 20 "https://purge.jsdelivr.net/gh/$REPO@dist/" >/dev/null || true
echo "  国内镜像：https://cdn.jsdelivr.net/gh/$REPO@dist/$(basename "$APK")"
