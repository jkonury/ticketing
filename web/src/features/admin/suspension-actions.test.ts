import { beforeEach, describe, expect, it, vi } from 'vitest';
import { headers } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { ApiError } from '@/lib/problem';
import {
  suspendMember,
  suspendPerformance,
  resumePerformance,
} from '@/server/api/admin';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import {
  changeMemberSuspensionAction,
  changePerformanceSuspensionAction,
} from './suspension-actions';

vi.mock('next/headers', () => ({ headers: vi.fn() }));
vi.mock('next/cache', () => ({ revalidatePath: vi.fn() }));
vi.mock('@/server/api/admin', () => ({
  suspendMember: vi.fn(),
  reinstateMember: vi.fn(),
  suspendPerformance: vi.fn(),
  resumePerformance: vi.fn(),
}));
vi.mock('@/server/auth/require-admin', async (original) => ({
  ...(await original<typeof import('@/server/auth/require-admin')>()),
  requireAdmin: vi.fn(),
}));
vi.mock('@/server/auth/require-user', () => ({
  withSessionRedirect: <T>(work: () => Promise<T>) => work(),
}));

function change(intent: string, reason?: string, id = '9') {
  const form = new FormData();
  form.set('id', id);
  form.set('intent', intent);
  if (reason !== undefined) form.set('reason', reason);
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

describe('관리자 정지 액션', () => {
  it('다른 출처의 요청을 거절한다', async () => {
    vi.mocked(headers).mockResolvedValue(
      new Headers({ origin: 'http://elsewhere.test' }) as never,
    );
    await expect(
      changeMemberSuspensionAction(initial, change('suspend', '운영 조치')),
    ).rejects.toThrow();
    expect(requireAdmin).not.toHaveBeenCalled();
    expect(suspendMember).not.toHaveBeenCalled();
  });

  it('화면을 연 이후에도 관리자 권한을 다시 확인한다', async () => {
    vi.mocked(requireAdmin).mockRejectedValue(new ForbiddenError());
    await expect(
      changePerformanceSuspensionAction(
        initial,
        change('suspend', '운영 조치'),
      ),
    ).resolves.toMatchObject({ message: expect.stringContaining('FORBIDDEN') });
    expect(suspendPerformance).not.toHaveBeenCalled();
  });

  it.each([
    ['공백 사유', change('suspend', '   ')],
    ['사유 길이 초과', change('suspend', '가'.repeat(201))],
    ['잘못된 대상', change('resume', undefined, '1e2')],
  ])('%s 요청을 처리하지 않는다', async (_, form) => {
    const result = await changePerformanceSuspensionAction(initial, form);
    expect(result.message).toContain('확인해 주세요');
    expect(suspendPerformance).not.toHaveBeenCalled();
    expect(resumePerformance).not.toHaveBeenCalled();
  });

  it('회원 정지 사유를 정리해 보내고 회원 목록을 갱신한다', async () => {
    await expect(
      changeMemberSuspensionAction(initial, change('suspend', '  운영 조치  ')),
    ).resolves.toMatchObject({ ok: true });
    expect(suspendMember).toHaveBeenCalledWith(9, '운영 조치');
    expect(revalidatePath).toHaveBeenCalledExactlyOnceWith('/admin/members');
  });

  it('공연 판매 재개에 사유를 요구하지 않고 공개 화면을 갱신한다', async () => {
    await expect(
      changePerformanceSuspensionAction(initial, change('resume')),
    ).resolves.toMatchObject({ ok: true });
    expect(resumePerformance).toHaveBeenCalledExactlyOnceWith(9);
    expect(vi.mocked(revalidatePath).mock.calls).toEqual([
      ['/admin/performances'],
      ['/'],
      ['/performances/9'],
    ]);
  });

  it('이미 중지된 공연은 새로고침할 수 있도록 안내한다', async () => {
    vi.mocked(suspendPerformance).mockRejectedValue(
      new ApiError(409, 'PERFORMANCE_SUSPENSION_UNCHANGED'),
    );
    const result = await changePerformanceSuspensionAction(
      initial,
      change('suspend', '운영 조치'),
    );
    expect(result.message).toContain('이미 요청한 상태');
    expect(revalidatePath).not.toHaveBeenCalled();
  });
});
