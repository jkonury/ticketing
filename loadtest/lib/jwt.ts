// Gateway·core가 검증하는 HS256 access 토큰을 k6가 직접 서명한다(로그인 bcrypt를 부하에 넣지 않기 위해서다).
import crypto from 'k6/crypto';
import encoding from 'k6/encoding';

const TOKEN_TTL_SECONDS = 3600;

function base64Url(value: string | ArrayBuffer): string {
  return encoding.b64encode(value, 'rawurl');
}

export function signAccessToken(memberId: number, role: string): string {
  const secret = __ENV.TICKETING_JWT_SECRET;
  if (!secret) {
    throw new Error('TICKETING_JWT_SECRET 환경 변수가 필요합니다.');
  }
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const payload = base64Url(JSON.stringify({ sub: String(memberId), role, iat: now, exp: now + TOKEN_TTL_SECONDS }));
  const signature = base64Url(crypto.hmac('sha256', secret, `${header}.${payload}`, 'binary'));
  return `${header}.${payload}.${signature}`;
}
