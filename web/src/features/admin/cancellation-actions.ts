'use server';

import { headers } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { z } from 'zod';
import { ApiError } from '@/lib/problem';
import { cancelPerformance, forceCancelBooking } from '@/server/api/admin';
import { SessionExpiredError } from '@/server/api/gateway-client';
import { assertSameOrigin } from '@/server/auth/csrf';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import { withSessionRedirect } from '@/server/auth/require-user';
import { parsePositiveId } from './admin-filters';

export type CancellationState = { message: string; ok?: boolean };

const input = z.object({
  id: z.string().transform(parsePositiveId).pipe(z.int().positive()),
  reason: z.string().trim().min(1).max(200),
});

const forbidden = '관리자만 취소할 수 있어요. (FORBIDDEN)';

const errors: Record<string, string> = {
  BOOKING_NOT_FOUND: '예매를 찾을 수 없어요.',
  PERFORMANCE_NOT_FOUND: '공연을 찾을 수 없어요.',
  INVALID_BOOKING_STATE:
    '현재 상태의 예매는 취소할 수 없어요. 화면을 새로고침해 주세요.',
  PERFORMANCE_CANCELLED: '이미 취소된 공연이에요. 목록을 새로고침해 주세요.',
  CONCURRENT_MODIFICATION: '상태가 변경됐어요. 화면을 새로고침해 주세요.',
  INVALID_REQUEST: '입력 내용을 확인해 주세요.',
};

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code && Object.hasOwn(errors, error.code))
      return errors[error.code];
    if (error.status === 403) return forbidden;
  }
  return '취소 요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.';
}

const operations = {
  booking: {
    cancel: forceCancelBooking,
    paths: (id: number) => ['/admin/bookings', `/admin/bookings/${id}`],
    message: '예매를 취소했어요.',
  },
  performance: {
    cancel: cancelPerformance,
    paths: (id: number) => [
      '/admin/performances',
      '/admin/bookings',
      '/',
      `/performances/${id}`,
    ],
    message: '공연을 취소했어요. 예매 취소와 환불은 순차적으로 진행돼요.',
  },
};

async function cancel(
  target: keyof typeof operations,
  form: FormData,
): Promise<CancellationState> {
  assertSameOrigin(new Headers(await headers()));
  try {
    await requireAdmin();
  } catch (error) {
    if (error instanceof ForbiddenError) return { message: forbidden };
    throw error;
  }

  const parsed = input.safeParse(Object.fromEntries(form));
  if (!parsed.success)
    return {
      message:
        '대상과 사유를 확인해 주세요. 사유는 200자까지 입력할 수 있어요.',
    };

  const { id, reason } = parsed.data;
  const operation = operations[target];
  const failure = await withSessionRedirect(async () => {
    try {
      await operation.cancel(id, reason);
    } catch (error) {
      if (error instanceof SessionExpiredError) throw error;
      return errorMessage(error);
    }
  });
  if (failure) return { message: failure };

  for (const path of operation.paths(id)) revalidatePath(path);
  return { ok: true, message: operation.message };
}

export async function forceCancelBookingAction(
  _state: CancellationState,
  form: FormData,
) {
  return cancel('booking', form);
}

export async function cancelPerformanceAction(
  _state: CancellationState,
  form: FormData,
) {
  return cancel('performance', form);
}
