'use client';
import { useActionState } from 'react';
import { registerAction } from './actions';
export function RegistrationForm() {
  const [state, action, pending] = useActionState(registerAction, {
    message: '',
  });
  return (
    <form action={action}>
      <label>
        공연장 이름 <input name="venueName" required maxLength={100} />
      </label>
      <label>
        구역 (쉼표 구분) <input name="sections" required placeholder="A,B" />
      </label>
      <label>
        열 수 <input type="number" name="rows" min="1" required />
      </label>
      <label>
        열당 좌석 수 <input type="number" name="perRow" min="1" required />
      </label>
      <fieldset>
        <legend>등급별 열 수와 가격</legend>
        {(['VIP', 'R', 'S', 'A'] as const).map((grade) => (
          <div key={grade}>
            <label>
              {grade} 열 수{' '}
              <input
                type="number"
                name={grade}
                min="0"
                defaultValue="0"
                required
              />
            </label>
            <label>
              {grade} 가격{' '}
              <input
                type="number"
                name={`${grade}Price`}
                min="0"
                defaultValue="0"
                required
              />
            </label>
          </div>
        ))}
      </fieldset>
      <label>
        공연 제목 <input name="title" required maxLength={200} />
      </label>
      <label>
        공연 설명 <textarea name="description" required />
      </label>
      <label>
        회차 시작 시각 (한국 시간){' '}
        <input type="datetime-local" name="startsAt" required />
      </label>
      {state.message && <p role="alert">{state.message}</p>}
      <button disabled={pending}>{pending ? '등록 중' : '등록'}</button>
    </form>
  );
}
