import { afterEach, describe, expect, it, vi } from 'vitest';
import {
  adminMemberPageSchema,
  auditLogPageSchema,
  roleChangeSchema,
  getAdminBooking,
  getAdminPayment,
  listAdminBookings,
  listAdminPerformances,
  reinstateMember,
  resumePerformance,
  suspendMember,
  suspendPerformance,
} from './admin';
import { createGatewayClient, gateway } from './gateway-client';
import {
  couponDefinitionSchema,
  couponPageSchema,
  grantResultSchema,
  issuedCouponPageSchema,
} from './admin-coupons';

describe('admin 스키마', () => {
  it('회원 목록을 파싱한다', () => {
    const parsed = adminMemberPageSchema.parse({
      items: [
        {
          id: 1,
          email: 'a@b.c',
          name: 'a',
          role: 'ADMIN',
          status: 'ACTIVE',
          createdAt: '2026-01-01T00:00:00Z',
        },
      ],
      nextCursor: null,
    });
    expect(parsed.items[0].role).toBe('ADMIN');
  });
  it('알 수 없는 역할을 거부한다', () => {
    expect(() =>
      roleChangeSchema.parse({ id: 1, email: 'a', name: 'a', role: 'ROOT' }),
    ).toThrow();
  });
  it('감사 로그를 파싱한다(actorId null 허용)', () => {
    const parsed = auditLogPageSchema.parse({
      items: [
        {
          id: 1,
          actorId: null,
          action: 'MEMBER_ROLE_CHANGED',
          targetType: 'MEMBER',
          targetId: '3',
          detail: { from: 'USER', to: 'ORGANIZER' },
          occurredAt: '2026-01-01T00:00:00Z',
        },
      ],
      nextCursor: 1,
    });
    expect(parsed.nextCursor).toBe(1);
  });
});

describe('쿠폰 스키마', () => {
  const definition = {
    id: 1,
    name: 'n',
    description: null,
    discountType: 'RATE',
    discountValue: 10,
    maxDiscount: 5000,
    minOrderAmount: 0,
    performanceId: null,
    validFrom: '2026-01-01T00:00:00Z',
    validUntil: '2026-02-01T00:00:00Z',
    issueMode: 'DIRECT',
    totalQuantity: null,
    status: 'ACTIVE',
    issueStartedAt: null,
  };
  it('목록과 stats를 파싱한다', () => {
    const parsed = couponPageSchema.parse({
      items: [
        {
          ...definition,
          stats: { issued: 1, reserved: 0, used: 2, revoked: 0 },
        },
      ],
      nextCursor: null,
    });
    expect(parsed.items[0].stats.used).toBe(2);
  });
  it('알 수 없는 발급 방식을 거부한다', () => {
    expect(() =>
      couponDefinitionSchema.parse({ ...definition, issueMode: 'X' }),
    ).toThrow();
  });
  it('발급 목록·지급 결과를 파싱한다', () => {
    expect(
      issuedCouponPageSchema.parse({
        items: [
          {
            id: 1,
            definitionId: 1,
            memberId: 2,
            status: 'EXPIRED',
            orderId: null,
            discountAmount: null,
            issuedAt: '2026-01-01T00:00:00Z',
            usedAt: null,
          },
        ],
        nextCursor: 3,
      }).nextCursor,
    ).toBe(3);
    expect(
      grantResultSchema.parse({ granted: [1], alreadyClaimed: [] }).granted,
    ).toEqual([1]);
  });
});

afterEach(() => vi.restoreAllMocks());

describe('관리자 API 호출', () => {
  const date = '2026-01-01T00:00:00Z';

  function stubGateway() {
    const fetch = vi.fn<typeof globalThis.fetch>();
    const client = createGatewayClient({
      fetch,
      sessions: {
        get: async () => ({
          memberId: 1,
          name: '관리자',
          role: 'ADMIN',
          accessToken: 'test-token',
          refreshToken: 'unused',
          accessExpiresAt: 60000,
        }),
        create: async () => 'session',
        update: async () => {},
        destroy: async () => {},
        tryLock: async () => true,
        unlock: async () => {},
      },
      sessionId: async () => 'session',
      clock: () => 0,
      baseUrl: 'http://gateway.test',
    });
    vi.spyOn(gateway, 'call').mockImplementation(client.call);
    return fetch;
  }

  it('공연·예매 목록의 필터와 커서를 요청에 전달하고 목록을 파싱한다', async () => {
    const fetch = stubGateway();
    const performances = {
      items: [
        {
          id: 1,
          organizerId: 3,
          title: '공연',
          venueName: '공연장',
          status: 'SUSPENDED',
          createdAt: date,
        },
      ],
      nextCursor: 1,
    };
    const bookings = {
      items: [
        {
          bookingId: 2,
          memberId: 3,
          scheduleId: 4,
          performanceTitle: '공연',
          status: 'PENDING',
          totalAmount: 1000,
          createdAt: date,
        },
      ],
      nextCursor: null,
    };
    fetch
      .mockResolvedValueOnce(Response.json(performances))
      .mockResolvedValueOnce(Response.json(bookings));
    await expect(
      listAdminPerformances({
        status: 'SUSPENDED',
        title: '공연 A',
        organizerId: 3,
        cursor: 9,
      }),
    ).resolves.toEqual(performances);
    await expect(
      listAdminBookings({
        status: 'PENDING',
        memberId: 3,
        scheduleId: 4,
        createdFrom: date,
      }),
    ).resolves.toEqual(bookings);
    expect(fetch.mock.calls.map(([url]) => String(url))).toEqual([
      'http://gateway.test/api/v1/admin/performances?status=SUSPENDED&title=%EA%B3%B5%EC%97%B0+A&organizerId=3&cursor=9',
      'http://gateway.test/api/v1/admin/bookings?status=PENDING&memberId=3&scheduleId=4&createdFrom=2026-01-01T00%3A00%3A00Z',
    ]);
    expect(
      new Headers(fetch.mock.calls[0][1]?.headers).get('Authorization'),
    ).toBe('Bearer test-token');
  });

  it('예매 상세와 결제 배열을 검증하고 잘못된 결제 응답을 거부한다', async () => {
    const fetch = stubGateway();
    const booking = {
      bookingId: 8,
      memberId: 3,
      scheduleId: 4,
      status: 'CONFIRMED',
      performanceTitle: '공연',
      startsAt: date,
      listAmount: 1000,
      discountAmount: 0,
      payableAmount: 1000,
      coupon: null,
      createdAt: date,
      expiresAt: date,
      seats: [{ seatId: 1, label: 'A1', price: 1000 }],
    };
    fetch
      .mockResolvedValueOnce(Response.json(booking))
      .mockResolvedValueOnce(Response.json([]))
      .mockResolvedValueOnce(Response.json([{ bookingId: 8 }]));
    await expect(getAdminBooking(8)).resolves.toEqual(booking);
    await expect(getAdminPayment(8)).resolves.toEqual([]);
    await expect(getAdminPayment(8)).rejects.toMatchObject({
      code: 'BAD_UPSTREAM_RESPONSE',
    });
    expect(fetch.mock.calls.map(([url]) => String(url))).toEqual([
      'http://gateway.test/api/v1/admin/bookings/8',
      'http://gateway.test/api/v1/admin/payments?bookingId=8',
      'http://gateway.test/api/v1/admin/payments?bookingId=8',
    ]);
  });

  it('회원과 공연에 정지 사유를 보내고 해제는 본문 없이 요청한다', async () => {
    const fetch = stubGateway().mockImplementation(async (_url, init) =>
      Response.json({
        id: 1,
        status: init?.method === 'PUT' ? 'SUSPENDED' : 'ACTIVE',
      }),
    );
    await expect(suspendMember(1, '회원 사유')).resolves.toEqual({
      id: 1,
      status: 'SUSPENDED',
    });
    await expect(reinstateMember(1)).resolves.toEqual({
      id: 1,
      status: 'ACTIVE',
    });
    await expect(suspendPerformance(1, '공연 사유')).resolves.toEqual({
      id: 1,
      status: 'SUSPENDED',
    });
    await expect(resumePerformance(1)).resolves.toEqual({
      id: 1,
      status: 'ACTIVE',
    });
    expect(
      fetch.mock.calls.map(([url, init]) => [
        new URL(String(url)).pathname,
        init?.method,
        init?.body,
      ]),
    ).toEqual([
      ['/api/v1/admin/members/1/suspension', 'PUT', '{"reason":"회원 사유"}'],
      ['/api/v1/admin/members/1/suspension', 'DELETE', undefined],
      [
        '/api/v1/admin/performances/1/suspension',
        'PUT',
        '{"reason":"공연 사유"}',
      ],
      ['/api/v1/admin/performances/1/suspension', 'DELETE', undefined],
    ]);
  });
});
