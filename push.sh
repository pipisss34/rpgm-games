#!/usr/bin/env bash
# Pakai di Termux (gh sudah login):  bash push.sh [nama-repo]
# Membuat repo private (kalau belum ada), commit, push, tunggu build, lalu unduh APK ke ./apk
set -euo pipefail
REPO="${1:-rpgm-webview}"

[ -d .git ] || git init -b main
git config user.name  >/dev/null 2>&1 || git config user.name  "$(gh api user -q .login)"
git config user.email >/dev/null 2>&1 || git config user.email "$(gh api user -q '.id')+$(gh api user -q .login)@users.noreply.github.com"

git add -A
git commit -m "Build APK" >/dev/null 2>&1 || echo "(tidak ada perubahan baru untuk di-commit)"

if git remote get-url origin >/dev/null 2>&1; then
  git push -u origin HEAD
else
  gh repo create "$REPO" --private --source=. --remote=origin --push
fi

echo "Menunggu workflow dimulai..."
sleep 8
RUN_ID=$(gh run list --workflow "Build APK" --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$RUN_ID" --exit-status

rm -rf apk
gh run download "$RUN_ID" -n RPGM-APK -D apk
echo "Selesai. APK ada di: $(pwd)/apk"
ls -lh apk
