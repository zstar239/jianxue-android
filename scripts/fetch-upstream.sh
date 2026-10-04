#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
revision=c08ae57cb88b6a4a46f4a5e9c1d6d11c5e69222e
archive="${TMPDIR:-/tmp}/jianxue-qingjian-${revision}.tar.gz"
if [[ -e vendor/qingjian ]]; then
  echo 'vendor/qingjian 已存在，保留现有内容。'
  exit 0
fi
mkdir -p vendor
curl --fail --location --retry 3 "https://codeload.github.com/qingjian-team/qingjian/tar.gz/${revision}" -o "$archive"
if [[ -f vendor/UPSTREAM_ARCHIVE_SHA256 ]]; then
  python3 -c 'import hashlib,sys; actual=hashlib.file_digest(open(sys.argv[1],"rb"),"sha256").hexdigest(); assert actual==open(sys.argv[2]).read().strip(), "上游源码校验失败"' "$archive" vendor/UPSTREAM_ARCHIVE_SHA256
fi
tar -xzf "$archive" -C vendor
mv "vendor/qingjian-${revision}" vendor/qingjian
printf '%s\n' "$revision" > vendor/UPSTREAM_REVISION
if [[ -f patches/0001-android-tls.patch ]]; then
  patch -d vendor/qingjian -p1 < patches/0001-android-tls.patch
fi
echo "已固定上游版本：${revision}"
