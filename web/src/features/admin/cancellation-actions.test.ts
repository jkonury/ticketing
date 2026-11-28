import { beforeEach, describe, expect, it, vi } from 'vitest';
import { headers } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { ApiError } from '@/lib/problem';
import { cancelPerformance, forceCancelBooking } from '@/server/api/admin';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import {
  cancelPerformanceAction,
  forceCancelBookingAction,
} from './cancellation-actions';

vi.mock('next/headers', () => ({ headers: vi.fn() }));
vi.mock('next/cache', () => ({ revalidatePath: vi.fn() }));
vi.mock('@/server/api/admin', () => ({
  forceCancelBooking: vi.fn(),
  cancelPerformance: vi.fn(),
}));
vi.mock('@/server/auth/require-admin', async (original) => ({
  ...(await original<typeof import('@/server/auth/require-admin')>()),
  requireAdmin: vi.fn(),
}));
vi.mock('@/server/auth/require-user', () => ({
  withSessionRedirect: <T>(work: () => Promise<T>) => work(),
}));

function cancellation(reason: string, id = '9') {
  const form = new FormData();
  form.set('id', id);
  form.set('reason', reason);
  return form;
}

const initial = { message: '' };

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(headers).mockResolvedValue(
    new Headers({ origin: 'http://localhost:13000' }) as never,
  );
  vi.mocked(requireAdmin).mockResolvedValue({
    memberId: 1,
    name: '관리자',
    role: 'ADMIN',
  });
});

describe('관리자 취소 액션', () => {
  it('다른 출처의 요청을 거절한다', async () => {
    vi.mocked(headers).mockResolvedValue(
      new Headers({ origin: 'http://elsewhere.test' }) as never,
    );
    await expect(
      forceCancelBookingAction(initial, cancellation('중복 예매')),
    ).rejects.toThrow();
    expect(forceCancelBooking).not.toHaveBeenCalled();
  });

  it.each([forceCancelBookingAction, cancelPerformanceAction])(
    '요청할 때마다 관리자 권한을 확인한다',
    async (action) => {
      vi.mocked(requireAdmin).mockRejectedValue(new ForbiddenError());
      await expect(
        action(initial, cancellation('운영 조치')),
      ).resolves.toMatchObject({
        message: expect.stringContaining('FORBIDDEN'),
      });
      expect(requireAdmin).toHaveBeenCalledOnce();
      expect(forceCancelBooking).not.toHaveBeenCalled();
      expect(cancelPerformance).not.toHaveBeenCalled();
    },
  );

  it.each([
    cancellation('   '),
    cancellation('가'.repeat(201)),
    cancellation('운영 조치', '1e2'),
  ])('잘못된 사유나 대상은 처리하지 않는다', async (form) => {
    const result = await cancelPerformanceAction(initial, form);
    expect(result.message).toContain('확인해 주세요');
    expect(cancelPerformance).not.toHaveBeenCalled();
  });

  it('정리한 강제 취소 사유를 보내고 예매를 갱신한다', async () => {
    await expect(
      forceCancelBookingAction(initial, cancellation('  중복 예매  ')),
    ).resolves.toMatchObject({ ok: true });
    expect(forceCancelBooking).toHaveBeenCalledExactlyOnceWith(9, '중복 예매');
    expect(vi.mocked(revalidatePath).mock.calls).toEqual([
      ['/admin/bookings'],
      ['/admin/bookings/9'],
    ]);
  });

  it('공연 취소 후 관리자 목록과 공개 화면을 갱신한다', async () => {
    await expect(
      cancelPerformanceAction(initial, cancellation('공연장 사정')),
    ).resolves.toMatchObject({ ok: true });
    expect(cancelPerformance).toHaveBeenCalledExactlyOnceWith(9, '공연장 사정');
    expect(vi.mocked(revalidatePath).mock.calls).toEqual([
      ['/admin/performances'],
      ['/admin/bookings'],
      ['/'],
      ['/performances/9'],
    ]);
  });

  it('취소할 수 없는 예매의 409 오류를 안내한다', async () => {
    vi.mocked(forceCancelBooking).mockRejectedValue(
      new ApiError(409, 'INVALID_BOOKING_STATE'),
    );
    const result = await forceCancelBookingAction(
      initial,
      cancellation('중복 예매'),
    );
    expect(result.message).toContain('현재 상태의 예매는 취소할 수 없어요');
    expect(revalidatePath).not.toHaveBeenCalled();
  });

  it('이미 취소된 공연의 409 오류를 안내한다', async () => {
    vi.mocked(cancelPerformance).mockRejectedValue(
      new ApiError(409, 'PERFORMANCE_CANCELLED'),
    );
    const result = await cancelPerformanceAction(
      initial,
      cancellation('공연장 사정'),
    );
    expect(result.message).toContain('이미 취소된 공연이에요');
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
