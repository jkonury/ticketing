'use server';

import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { z } from 'zod';
import { ApiError } from '@/lib/problem';
import { assertSameOrigin } from '@/server/auth/csrf';
import { login, logout, signup } from '@/server/auth/auth-service';

// 제출 실패 시 React 폼 액션이 폼을 초기화하므로 비밀번호를 뺀 입력값을 돌려줘 defaultValue로 복원한다.
export type AuthState = {
  message: string;
  fields?: { email?: string; name?: string; role?: string };
};
function text(value: FormDataEntryValue | null) {
  return typeof value === 'string' ? value : undefined;
}
function keptFields(form: FormData) {
  return {
    email: text(form.get('email')),
    name: text(form.get('name')),
    role: text(form.get('role')),
  };
}
const loginSchema = z.object({
  email: z.email(),
  password: z.string().min(8).max(64),
});
const signupSchema = loginSchema.extend({
  name: z.string().min(1).max(50),
  role: z.enum(['USER', 'ORGANIZER']),
});
function message(error: unknown) {
  if (error instanceof ApiError) {
    if (error.code === 'INVALID_CREDENTIALS')
      return '이메일 또는 비밀번호가 올바르지 않아요.';
    if (error.code === 'EMAIL_ALREADY_EXISTS')
      return '이미 사용 중인 이메일이에요.';
    if (error.code === 'CSRF_REJECTED') return '요청 출처를 확인해 주세요.';
    if (error.code === 'INVALID_REQUEST') return '입력한 내용을 확인해 주세요.';
  }
  return '요청을 처리하지 못했어요. 잠시 뒤 다시 시도해 주세요.';
}
function safeNext(value: FormDataEntryValue | null) {
  if (
    typeof value !== 'string' ||
    !value.startsWith('/') ||
    value.startsWith('//') ||
    value.includes('\\')
  )
    return '/';
  return value;
}
export async function loginAction(
  _state: AuthState,
  form: FormData,
): Promise<AuthState> {
  try {
    assertSameOrigin(new Headers(await headers()));
    const input = loginSchema.safeParse({
      email: form.get('email'),
      password: form.get('password'),
    });
    if (!input.success)
      return {
        message: '입력한 내용을 확인해 주세요.',
        fields: keptFields(form),
      };
    await login(input.data);
  } catch (error) {
    return { message: message(error), fields: keptFields(form) };
  }
  redirect(safeNext(form.get('next')));
}
export async function signupAction(
  _state: AuthState,
  form: FormData,
): Promise<AuthState> {
  try {
    assertSameOrigin(new Headers(await headers()));
    const input = signupSchema.safeParse({
      email: form.get('email'),
      password: form.get('password'),
      name: form.get('name'),
      role: form.get('role'),
    });
    if (!input.success)
      return {
        message: '입력한 내용을 확인해 주세요.',
        fields: keptFields(form),
      };
    await signup(input.data);
  } catch (error) {
    return { message: message(error), fields: keptFields(form) };
  }
  redirect('/login');
}
export async function logoutAction() {
  assertSameOrigin(new Headers(await headers()));
  await logout();
  redirect('/');
}
