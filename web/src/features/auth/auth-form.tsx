'use client';

import { useActionState } from 'react';
import { FormMessage } from '@/components/form-message';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input, Select } from '@/components/ui/input';
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
    <form className="grid gap-4" action={action}>
      {mode === 'signup' && (
        <>
          <Field label="이름">
            {(control) => (
              <Input
                name="name"
                required
                maxLength={50}
                defaultValue={state.fields?.name}
                {...control}
              />
            )}
          </Field>
          <Field label="역할">
            {(control) => (
              <Select
                name="role"
                defaultValue={state.fields?.role ?? 'USER'}
                {...control}
              >
                <option value="USER">관람객</option>
                <option value="ORGANIZER">주최자</option>
              </Select>
            )}
          </Field>
        </>
      )}
      <Field label="이메일">
        {(control) => (
          <Input
            name="email"
            type="email"
            required
            defaultValue={state.fields?.email}
            {...control}
          />
        )}
      </Field>
      <Field label="비밀번호" hint="비밀번호는 8~64자로 입력해 주세요.">
        {(control) => (
          <Input
            name="password"
            type="password"
            required
            minLength={8}
            maxLength={64}
            {...control}
          />
        )}
      </Field>
      {next && <input name="next" type="hidden" value={next} />}
      <FormMessage message={state.message} />
      <Button type="submit" loading={pending}>
        {pending ? '처리 중이에요' : mode === 'login' ? '로그인' : '가입'}
      </Button>
    </form>
  );
}
