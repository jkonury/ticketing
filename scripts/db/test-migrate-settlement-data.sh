#!/usr/bin/env bash
# Testcontainers의 격리 DB와 Kafka에서 이전·전환 절차를 검증한다.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec ./scripts/agent/test/run-tests.sh :settlement:test --tests '*SettlementCutoverIntegrationTests'
