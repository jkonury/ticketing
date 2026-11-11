#!/usr/bin/env bash
# 저장소 git hook 경로를 .githooks로 지정한다. 클론 후 한 번 실행한다.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"
chmod +x .githooks/*
git config core.hooksPath .githooks
echo "core.hooksPath=.githooks 설정 완료"
