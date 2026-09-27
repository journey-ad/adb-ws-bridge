#!/usr/bin/env bash
# 生成 latest.json 并强推到 index 分支，供前端读取下载入口
# TAG 为空时取最新 release
set -euo pipefail

mkdir -p public
if [ -n "${TAG:-}" ]; then
    gh api "repos/$REPO/releases/tags/$TAG" > release.json
else
    gh api "repos/$REPO/releases/latest" > release.json
fi
VERSION=$(python3 -c "import json;print(json.load(open('release.json'))['tag_name'])")
gh api "repos/$REPO/commits/$VERSION" --jq .sha > commit.txt
COMMIT="$(cat commit.txt)" python3 - <<'PY'
import json, os

release = json.load(open("release.json"))
commit = os.environ["COMMIT"].strip()


def find(suffix):
    for asset in release["assets"]:
        if asset["name"].endswith(suffix):
            return {"url": asset["browser_download_url"], "size": asset["size"]}
    return None


data = {
    "version": release["tag_name"],
    "repository": os.environ["REPO"],
    "commit": commit,
    "shortCommit": commit[:7],
    "publishedAt": release["published_at"],
    "releaseUrl": release["html_url"],
    "assets": {
        "universal": find("-universal.apk"),
        "arm64-v8a": find("-arm64-v8a.apk"),
        "armeabi-v7a": find("-armeabi-v7a.apk"),
        "x86_64": find("-x86_64.apk"),
    },
}
with open("public/latest.json", "w") as f:
    json.dump(data, f, ensure_ascii=False, indent=2)
PY

cd public
git init -b index
git add latest.json
git -c user.name="github-actions[bot]" \
    -c user.email="41898282+github-actions[bot]@users.noreply.github.com" \
    commit -m "chore：更新 latest.json"
git push --force "https://x-access-token:$GH_TOKEN@github.com/$REPO.git" index
