#!/usr/bin/env bash
# Import complete v1 and final snapshots into a NEW repository.
# Usage (Git Bash): bash create-history.sh /c/path/v1 /c/path/final /c/path/new-repo
# Explicit import dates; these do not establish when the code was written.
set -euo pipefail
fail() { printf 'Error: %s\n' "$*" >&2; exit 1; }
[[ $# == 3 ]] || fail 'Provide v1 directory, final directory, and a new destination.'
v1=$(cd -- "$1" && pwd -P)
final=$(cd -- "$2" && pwd -P)
[[ -f "$v1/pom.xml" && -f "$final/pom.xml" ]] || fail 'Both snapshots must contain pom.xml.'
[[ ! -e "$3" ]] || fail 'Destination must not exist; existing history is never overwritten.'
mkdir -p -- "$3"
cd -- "$3"
destination=$(pwd -P)
case "$destination/" in "$v1/"*|"$final/"*) fail 'Destination must be outside both source folders.' ;; esac
git init -b main
git var GIT_AUTHOR_IDENT >/dev/null
git var GIT_COMMITTER_IDENT >/dev/null
copy_snapshot() {
  # Skip local data/build files and the duplicate nested project in supplied v1.
  tar -C "$1" --exclude='./.git' --exclude='./target' --exclude='./node_modules' \
    --exclude='./.env' --exclude='./.env.local' --exclude='./study-planner' -cf - . | tar -xf -
  git add --all
  git update-index --chmod=+x -- mvnw
  if [[ -f scripts/create-history.sh ]]; then
    git update-index --chmod=+x -- scripts/create-history.sh
  fi
}
copy_snapshot "$v1"
GIT_AUTHOR_DATE='2026-11-10T10:15:00+04:00' GIT_COMMITTER_DATE='2026-11-10T10:15:00+04:00' \
  git -c commit.gpgsign=false commit -m 'Initial version'
# Remove only tracked files in the new import repository before copying final.
git rm -r --quiet -- .
copy_snapshot "$final"
GIT_AUTHOR_DATE='2026-11-14T16:45:00+04:00' GIT_COMMITTER_DATE='2026-11-14T16:45:00+04:00' \
  git -c commit.gpgsign=false commit --allow-empty -m 'Update to final version'
git log --reverse --format='%h %aI %cI %s'
# Push separately after reviewing: git remote add origin URL; git push -u origin main
