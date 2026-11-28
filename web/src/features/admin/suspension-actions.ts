'use server';

import { headers } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { z } from 'zod';
import { ApiError } from '@/lib/problem';
import {
  reinstateMember,
  resumePerformance,
  suspendMember,
  suspendPerformance,
} from '@/server/api/admin';
import { assertSameOrigin } from '@/server/auth/csrf';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import { SessionExpiredError } from '@/server/api/gateway-client';
import { parsePositiveId } from './admin-filters';
import { withSessionRedirect } from '@/server/auth/require-user';

export type SuspensionState = { message: string; ok?: boolean };

const positiveId = z
  .string()
  .transform(parsePositiveId)
  .pipe(z.int().positive());

const input = z.discriminatedUnion('intent', [
  z.object({
    id: positiveId,
    intent: z.literal('suspend'),
    reason: z.string().max(200).trim().min(1),
  }),
  z.object({ id: positiveId, intent: z.literal('resume') }),
]);

const forbidden = '관리자만 변경할 수 있어요. (FORBIDDEN)';
const errors: Record<string, string> = {
  MEMBER_NOT_FOUND: '회원을 찾을 수 없어요.',
  PERFORMANCE_NOT_FOUND: '공연을 찾을 수 없어요.',
  MEMBER_SUSPENSION_FORBIDDEN: '관리자는 이용을 정지할 수 없어요.',
  MEMBER_SUSPENSION_UNCHANGED:
    '회원이 이미 요청한 상태예요. 목록을 새로고침해 주세요.',
  PERFORMANCE_SUSPENSION_UNCHANGED:
    '공연이 이미 요청한 상태예요. 목록을 새로고침해 주세요.',
  PERFORMANCE_CANCELLED: '이미 취소된 공연이에요. 목록을 새로고침해 주세요.',
  INVALID_REQUEST: '입력 내용을 확인해 주세요.',
};

function errorMessage(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code && Object.hasOwn(errors, error.code))
      return errors[error.code];
    if (error.status === 403) return forbidden;
  }
  return '요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.';
}

const operations = {
  member: {
    suspend: suspendMember,
    resume: reinstateMember,
    path: '/admin/members',
    messages: {
      suspend: '회원 이용을 정지했어요.',
      resume: '회원 이용을 다시 허용했어요.',
    },
  },
  performance: {
    suspend: suspendPerformance,
    resume: resumePerformance,
    path: '/admin/performances',
    messages: {
      suspend: '공연 판매를 중지했어요.',
      resume: '공연 판매를 재개했어요.',
    },
  },
};

async function changeSuspension(
  target: keyof typeof operations,
  form: FormData,
): Promise<SuspensionState> {
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

  const data = parsed.data;
  const operation = operations[target];
  const failure = await withSessionRedirect(async () => {
    try {
      if (data.intent === 'suspend')
        await operation.suspend(data.id, data.reason);
      else await operation.resume(data.id);
    } catch (error) {
      if (error instanceof SessionExpiredError) throw error;
      return errorMessage(error);
    }
  });
  if (failure) return { message: failure };

  revalidatePath(operation.path);
  if (target === 'performance') {
    revalidatePath('/');
    revalidatePath(`/performances/${data.id}`);
  }
  return { ok: true, message: operation.messages[data.intent] };
}

export async function changeMemberSuspensionAction(
  _state: SuspensionState,
  form: FormData,
) {
  return changeSuspension('member', form);
}

export async function changePerformanceSuspensionAction(
  _state: SuspensionState,
  form: FormData,
) {
  return changeSuspension('performance', form);
}
