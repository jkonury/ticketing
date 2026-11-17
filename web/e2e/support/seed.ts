import { expect, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export async function seed(request: APIRequestContext) {
  const gateway = process.env.E2E_GATEWAY_URL ?? 'http://localhost:18000';
  const unique = randomUUID();
  const organizer = {
    email: `organizer-${unique}@example.com`,
    password: 'test-password-123',
  };
  const post = async (path: string, data: unknown, token?: string) => {
    const response = await request.post(`${gateway}/api/v1${path}`, {
      data,
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    expect(response.ok(), `${path}: ${response.status()}`).toBe(true);
    return response.json();
  };
  await post('/members', {
    ...organizer,
    name: 'E2E 주최자',
    role: 'ORGANIZER',
  });
  const login = await post('/auth/tokens', organizer);
  const title = `E2E 공연 ${unique}`;
  const venue = await post(
    '/venues',
    {
      name: `E2E 공연장 ${unique}`,
      seats: Array.from({ length: 8 }, (_, index) => ({
        section: 'A',
        row: '1',
        number: index + 1,
        grade: 'A',
      })),
    },
    login.accessToken,
  );
  const performance = await post(
    '/performances',
    {
      title,
      description: 'E2E 테스트 공연',
      venueId: venue.id,
      prices: [{ grade: 'A', price: 10000 }],
    },
    login.accessToken,
  );
  const schedule = await post(
    `/performances/${performance.id}/schedules`,
    {
      startsAt: new Date(Date.now() + 86400_000).toISOString(),
    },
    login.accessToken,
  );
  return {
    organizer,
    title,
    performanceId: performance.id,
    scheduleId: schedule.id,
  };
}

function gatewayUrl() {
  return process.env.E2E_GATEWAY_URL ?? 'http://localhost:18000';
}

export async function registerMember(
  request: APIRequestContext,
  member: { email: string; password: string; name: string },
) {
  const response = await request.post(`${gatewayUrl()}/api/v1/members`, {
    data: member,
  });
  expect(response.ok(), `회원 가입: ${response.status()}`).toBe(true);
  const body = await response.json();
  return body.id as number;
}

export async function grantFixedCoupon(
  request: APIRequestContext,
  options: { name: string; discountValue: number; memberIds: number[] },
) {
  const email = process.env.E2E_ADMIN_EMAIL;
  const password = process.env.E2E_ADMIN_PASSWORD;
  if (!email || !password) {
    throw new Error(
      'E2E_ADMIN_EMAIL, E2E_ADMIN_PASSWORD 환경 변수가 필요합니다 (scripts/e2e/run.sh가 설정).',
    );
  }
  const base = `${gatewayUrl()}/api/v1`;
  const tokenResponse = await request.post(`${base}/auth/tokens`, {
    data: { email, password },
  });
  expect(tokenResponse.ok(), `관리자 로그인: ${tokenResponse.status()}`).toBe(
    true,
  );
  const headers = {
    Authorization: `Bearer ${(await tokenResponse.json()).accessToken}`,
  };
  const now = Date.now();
  const created = await request.post(`${base}/admin/coupons`, {
    headers,
    data: {
      name: options.name,
      description: 'E2E 정액 할인',
      discountType: 'FIXED',
      discountValue: options.discountValue,
      maxDiscount: null,
      minOrderAmount: 0,
      performanceId: null,
      validFrom: new Date(now - 3600_000).toISOString(),
      validUntil: new Date(now + 7 * 86400_000).toISOString(),
      issueMode: 'DIRECT',
      totalQuantity: null,
    },
  });
  expect(created.ok(), `쿠폰 정의 생성: ${created.status()}`).toBe(true);
  const definition = await created.json();
  const granted = await request.post(
    `${base}/admin/coupons/${definition.id}/grants`,
    { headers, data: { memberIds: options.memberIds } },
  );
  expect(granted.ok(), `쿠폰 지급: ${granted.status()}`).toBe(true);
}
