#!/usr/bin/env bash

# 개발·부하 테스트에서 모듈별 설정 선택을 공유한다.
scouter_config_file() {
  case "$1" in
    gateway) printf '%s\n' ticketing-gateway.conf ;;
    realtime) printf '%s\n' ticketing-realtime.conf ;;
    *) printf '%s\n' ticketing.conf ;;
  esac
}
