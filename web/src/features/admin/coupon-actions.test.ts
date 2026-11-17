import { beforeEach, describe, expect, it, vi } from 'vitest';
import { headers } from 'next/headers';
import { ApiError } from '@/lib/problem';
import {
  createCoupon,
  grantCoupons,
  revokeIssuedCoupon,
  stopCoupon,
  updateCoupon,
} from '@/server/api/admin-coupons';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import {
  createCouponAction,
  grantCouponsAction,
  revokeCouponAction,
  stopCouponAction,
  updateCouponAction,
} from './coupon-actions';

vi.mock('next/headers', () => ({ headers: vi.fn() }));
vi.mock('next/cache', () => ({ revalidatePath: vi.fn() }));
vi.mock('next/navigation', () => ({ redirect: vi.fn() }));
vi.mock('@/server/api/admin-coupons', () => ({
  createCoupon: vi.fn(),
  updateCoupon: vi.fn(),
  stopCoupon: vi.fn(),
  grantCoupons: vi.fn(),
  revokeIssuedCoupon: vi.fn(),
}));
vi.mock('@/server/auth/require-admin', async (original) => ({
  ...(await original<typeof import('@/server/auth/require-admin')>()),
  requireAdmin: vi.fn(),
}));
vi.mock('@/server/auth/require-user', () => ({
  withSessionRedirect: <T>(work: () => Promise<T>) => work(),
}));

const empty = { message: '' };
function form(values: Record<string, string>) {
  const data = new FormData();
  for (const [k, v] of Object.entries(values)) data.set(k, v);
  return data;
}
const actions = [
  ['생성', createCouponAction, createCoupon, { name: 'a' }],
  ['수정', updateCouponAction, updateCoupon, { id: '1', name: 'a' }],
  ['중지', stopCouponAction, stopCoupon, { id: '1' }],
  ['지급', grantCouponsAction, grantCoupons, { id: '1', memberIds: '1,2' }],
  [
    '회수',
    revokeCouponAction,
    revokeIssuedCoupon,
    { issuedCouponId: '5', definitionId: '1' },
  ],
] as const;

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(headers).mockResolvedValue(
    new Headers({ origin: 'http://localhost:13000' }) as never,
  );
  vi.mocked(requireAdmin).mockResolvedValue({
    memberId: 1,
    name: 'a',
    role: 'ADMIN',
  });
});

describe.each(actions)('쿠폰 %s 액션', (_name, action, api, values) => {
  it('Origin이 다르면 거부한다', async () => {
    vi.mocked(headers).mockResolvedValue(
      new Headers({ origin: 'http://evil.test' }) as never,
    );
    await expect(action(empty, form(values))).rejects.toThrow();
    expect(api).not.toHaveBeenCalled();
  });
  it('관리자가 아니면 거부한다', async () => {
    vi.mocked(requireAdmin).mockRejectedValue(new ForbiddenError());
    const result = await action(empty, form(values));
    expect(result.message).toContain('FORBIDDEN');
    expect(api).not.toHaveBeenCalled();
  });
});

describe('쿠폰 액션 오류 매핑', () => {
  it.each([
    ['COUPON_DEFINITION_LOCKED', updateCouponAction, updateCoupon],
    ['COUPON_VALID_UNTIL_SHORTENED', updateCouponAction, updateCoupon],
    ['COUPON_NOT_ISSUABLE', grantCouponsAction, grantCoupons],
    ['COUPON_NOT_REVOCABLE', revokeCouponAction, revokeIssuedCoupon],
    ['COUPON_NOT_FOUND', stopCouponAction, stopCoupon],
  ] as const)('%s를 사용자 메시지로 바꾼다', async (code, action, api) => {
    (vi.mocked(api) as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(
      new ApiError(409, code),
    );
    const values = {
      id: '1',
      name: 'a',
      memberIds: '1',
      issuedCouponId: '5',
      definitionId: '1',
    };
    const result = await action(empty, form(values));
    expect(result.message).toContain(`(${code})`);
  });
  it('알 수 없는 오류는 일반 메시지', async () => {
    vi.mocked(stopCoupon).mockRejectedValue(new ApiError(500, 'X'));
    const result = await stopCouponAction(empty, form({ id: '1' }));
    expect(result.message).toContain('다시 시도');
  });
  it('잘못된 회원 ID 입력은 API를 호출하지 않는다', async () => {
    const result = await grantCouponsAction(
      empty,
      form({ id: '1', memberIds: 'a' }),
    );
    expect(result.message).toContain('숫자');
    expect(grantCoupons).not.toHaveBeenCalled();
  });
  it('지급 결과에 granted·alreadyClaimed를 보여 준다', async () => {
    vi.mocked(grantCoupons).mockResolvedValue({
      granted: [1],
      alreadyClaimed: [2],
    });
    const result = await grantCouponsAction(
      empty,
      form({ id: '1', memberIds: '1,2' }),
    );
    expect(result.message).toContain('지급 1명');
    expect(result.message).toContain('이미 보유: 2');
    expect(grantCoupons).toHaveBeenCalledWith(1, [1, 2]);
  });
});
