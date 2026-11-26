'use client';
import { useActionState } from 'react';
import { Button } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { InlineError } from '@/components/ui/feedback';
import { Input, Textarea } from '@/components/ui/input';
import { registerAction } from './actions';
export function RegistrationForm() {
  const [state, action, pending] = useActionState(registerAction, {
    message: '',
  });
  return (
    <form action={action} className="grid max-w-160 gap-4">
      <Field label="공연장 이름">
        {(c) => <Input {...c} name="venueName" required maxLength={100} />}
      </Field>
      <Field label="구역 (쉼표 구분)">
        {(c) => <Input {...c} name="sections" required placeholder="A,B" />}
      </Field>
      <Field label="열 수">
        {(c) => <Input {...c} type="number" name="rows" min="1" required />}
      </Field>
      <Field label="열당 좌석 수">
        {(c) => <Input {...c} type="number" name="perRow" min="1" required />}
      </Field>
      <fieldset className="min-w-0 rounded-card border border-line bg-surface p-4">
        <legend className="px-1 text-caption font-semibold">
          등급별 열 수와 가격
        </legend>
        <div className="grid gap-4">
          {(['VIP', 'R', 'S', 'A'] as const).map((grade) => (
            <div key={grade} className="grid gap-4 sm:grid-cols-2">
              <Field label={`${grade} 열 수`}>
                {(c) => (
                  <Input
                    {...c}
                    type="number"
                    name={grade}
                    min="0"
                    defaultValue="0"
                    required
                  />
                )}
              </Field>
              <Field label={`${grade} 가격`}>
                {(c) => (
                  <Input
                    {...c}
                    type="number"
                    name={`${grade}Price`}
                    min="0"
                    defaultValue="0"
                    required
                  />
                )}
              </Field>
            </div>
          ))}
        </div>
      </fieldset>
      <Field label="공연 제목">
        {(c) => <Input {...c} name="title" required maxLength={200} />}
      </Field>
      <Field label="공연 설명">
        {(c) => <Textarea {...c} name="description" required />}
      </Field>
      <Field label="회차 시작 시각 (한국 시간)">
        {(c) => <Input {...c} type="datetime-local" name="startsAt" required />}
      </Field>
      {state.message && <InlineError role="alert">{state.message}</InlineError>}
      <div>
        <Button type="submit" disabled={pending}>
          {pending ? '등록 중' : '등록'}
        </Button>
      </div>
    </form>
  );
}
