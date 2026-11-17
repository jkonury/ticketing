'use server';
import { headers } from 'next/headers';
import { revalidatePath } from 'next/cache';
import { z } from 'zod';
import { ApiError } from '@/lib/problem';
import { changeMemberRole, roleSchema } from '@/server/api/admin';
import { assertSameOrigin } from '@/server/auth/csrf';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';
import { withSessionRedirect } from '@/server/auth/require-user';

export type RoleChangeState = { message: string };
const schema = z.object({
  memberId: z.coerce.number().int().positive(),
  role: roleSchema,
});

export async function changeRoleAction(
  _state: RoleChangeState,
  form: FormData,
): Promise<RoleChangeState> {
  assertSameOrigin(new Headers(await headers()));
  try {
    await requireAdmin();
  } catch (error) {
    if (error instanceof ForbiddenError)
      return { message: '관리자만 변경할 수 있습니다. (FORBIDDEN)' };
    throw error;
  }
  const parsed = schema.safeParse(Object.fromEntries(form));
  if (!parsed.success)
    return { message: '입력 내용을 확인해 주세요. (INVALID_REQUEST)' };
  try {
    const member = await withSessionRedirect(() =>
      changeMemberRole(parsed.data.memberId, parsed.data.role),
    );
    revalidatePath('/admin/members');
    return {
      message: `${member.email}의 역할을 ${member.role}로 변경했습니다.`,
    };
  } catch (error) {
    if (error instanceof ApiError) {
      if (error.code === 'LAST_ADMIN')
        return {
          message: '마지막 관리자의 역할은 변경할 수 없습니다. (LAST_ADMIN)',
        };
      if (error.code === 'MEMBER_NOT_FOUND')
        return { message: '회원을 찾을 수 없습니다. (MEMBER_NOT_FOUND)' };
      if (error.status === 403)
        return { message: '관리자만 변경할 수 있습니다. (FORBIDDEN)' };
    }
    return { message: '요청을 처리하지 못했습니다. 다시 시도해 주세요.' };
  }
}
