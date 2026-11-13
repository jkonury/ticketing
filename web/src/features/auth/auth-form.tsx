'use client';

import { useActionState } from 'react';
import { FormMessage } from '@/components/form-message';
import { loginAction, signupAction } from './actions';

export function AuthForm({
  mode,
  next,
}: {
  mode: 'login' | 'signup';
  next?: string;
}) {
  const [state, action, pending] = useActionState(
    mode === 'login' ? loginAction : signupAction,
    { message: '' },
  );
  return (
    <form className="stacked-form" action={action}>
      {mode === 'signup' && (
        <>
          <label>
            이름 <input name="name" required maxLength={50} />
          </label>
          <label>
            역할{' '}
            <select name="role">
              <option value="USER">관객</option>
              <option value="ORGANIZER">주최자</option>
            </select>
          </label>
        </>
      )}
      <label>
        이메일 <input name="email" type="email" required />
      </label>
      <label>
        비밀번호{' '}
        <input
          name="password"
          type="password"
          required
          minLength={8}
          maxLength={64}
          aria-describedby="password-hint"
        />
      </label>
      <p id="password-hint" className="field-hint">
        비밀번호는 8~64자로 입력해 주세요.
      </p>
      {next && <input name="next" type="hidden" value={next} />}
      <FormMessage message={state.message} />
      <button disabled={pending}>
        {pending ? '처리 중' : mode === 'login' ? '로그인' : '가입'}
      </button>
    </form>
  );
}
