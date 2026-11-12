'use server';

import { headers } from 'next/headers';
import { redirect } from 'next/navigation';
import { z } from 'zod';
import { ApiError } from '@/lib/problem';
import { assertSameOrigin } from '@/server/auth/csrf';
import { login, logout, signup } from '@/server/auth/auth-service';

export type AuthState = { message: string };
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
      return '이메일 또는 비밀번호가 올바르지 않습니다.';
    if (error.code === 'EMAIL_ALREADY_EXISTS')
      return '이미 사용 중인 이메일입니다.';
    if (error.code === 'CSRF_REJECTED') return '요청 출처를 확인해 주세요.';
    if (error.code === 'INVALID_REQUEST') return '입력 내용을 확인해 주세요.';
  }
  return '요청을 처리하지 못했습니다. 다시 시도해 주세요.';
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
    if (!input.success) return { message: '입력 내용을 확인해 주세요.' };
    await login(input.data);
  } catch (error) {
    return { message: message(error) };
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
    if (!input.success) return { message: '입력 내용을 확인해 주세요.' };
    await signup(input.data);
  } catch (error) {
    return { message: message(error) };
  }
  redirect('/login');
}
export async function logoutAction() {
  assertSameOrigin(new Headers(await headers()));
  await logout();
  redirect('/');
}
