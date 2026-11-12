#!/usr/bin/env bash
# source해서 쓴다: sign_jwt <memberId> <role>  -> HS256 access 토큰을 stdout으로 출력한다.
# JWT_SECRET은 저장소 루트의 .env에서 읽고 출력하지 않는다.

_b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

_load_jwt_secret() {
  local root env_file line
  root=$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)
  env_file="$root/.env"
  [[ -f $env_file ]] || { echo '.env 파일이 필요합니다.' >&2; return 1; }
  line=$(grep -E '^JWT_SECRET=' "$env_file" | tail -n 1 || true)
  [[ -n $line ]] || { echo '.env에 JWT_SECRET이 없습니다.' >&2; return 1; }
  line=${line#JWT_SECRET=}
  line=${line%\"}; line=${line#\"}
  line=${line%\'}; line=${line#\'}
  JWT_SECRET_VALUE=$line
}

sign_jwt() {
  local member_id=${1:?memberId가 필요합니다} role=${2:?role이 필요합니다}
  [[ -n ${JWT_SECRET_VALUE:-} ]] || _load_jwt_secret || return 1
  local now exp header payload signature
  now=$(date +%s)
  exp=$((now + 3600))
  header=$(printf '{"alg":"HS256","typ":"JWT"}' | _b64url)
  payload=$(printf '{"sub":"%s","role":"%s","iat":%s,"exp":%s}' "$member_id" "$role" "$now" "$exp" | _b64url)
  signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$JWT_SECRET_VALUE" -binary | _b64url)
  printf '%s.%s.%s\n' "$header" "$payload" "$signature"
}
