import { expect, type APIRequestContext } from '@playwright/test';
import { randomUUID } from 'node:crypto';

export async function seed(request: APIRequestContext) {
  const gateway = process.env.E2E_GATEWAY_URL ?? 'http://localhost:8000';
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
