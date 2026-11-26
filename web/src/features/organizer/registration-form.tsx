'use client';
import { useActionState, useMemo, useState, type FormEvent } from 'react';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Field } from '@/components/ui/field';
import { InlineError } from '@/components/ui/feedback';
import { Input, Textarea } from '@/components/ui/input';
import { registerAction } from './actions';
import { SeatPreview } from './seat-preview';
import { grades, previewLayout, type Grade } from './seats';

const num = (value: string) => (value === '' ? NaN : Number(value));

export function RegistrationForm() {
  const [state, action, pending] = useActionState(registerAction, {
    message: '',
  });
  const [sections, setSections] = useState('');
  const [rows, setRows] = useState('');
  const [perRow, setPerRow] = useState('');
  const [counts, setCounts] = useState<Record<Grade, string>>({
    VIP: '0',
    R: '0',
    S: '0',
    A: '0',
  });
  const [submitted, setSubmitted] = useState(false);

  const preview = useMemo(
    () =>
      previewLayout({
        sections,
        rows: num(rows),
        perRow: num(perRow),
        counts: {
          VIP: num(counts.VIP),
          R: num(counts.R),
          S: num(counts.S),
          A: num(counts.A),
        },
      }),
    [sections, rows, perRow, counts],
  );
  const filled = sections.trim() !== '' && rows !== '' && perRow !== '';
  const show = filled || submitted;
  const error = preview.error;
  const sectionError =
    show && error === 'INVALID_SEATS'
      ? '구역 이름을 쉼표로 구분해 중복 없이 20자 이내로 입력해 주세요.'
      : undefined;
  const gradeError =
    show && error === 'ROW_COUNT_MISMATCH'
      ? '등급별 열 수의 합이 전체 열 수와 같아야 해요.'
      : show && error === 'TOO_MANY_SEATS'
        ? '전체 좌석은 5000석을 넘길 수 없어요. 구역·열·좌석 수를 줄여 주세요.'
        : undefined;

  function onSubmit(event: FormEvent<HTMLFormElement>) {
    setSubmitted(true);
    if (preview.error) event.preventDefault();
  }

  return (
    <form
      action={action}
      onSubmit={onSubmit}
      className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,24rem)]"
    >
      <div className="grid gap-4">
        <Field label="공연 제목">
          {(c) => <Input {...c} name="title" required maxLength={200} />}
        </Field>
        <Field label="공연 설명">
          {(c) => <Textarea {...c} name="description" required />}
        </Field>
        <Field label="회차 시작 시각 (한국 시간)">
          {(c) => (
            <Input {...c} type="datetime-local" name="startsAt" required />
          )}
        </Field>
        <Field label="공연장 이름">
          {(c) => <Input {...c} name="venueName" required maxLength={100} />}
        </Field>
        <Field label="구역 (쉼표 구분)" error={sectionError}>
          {(c) => (
            <Input
              {...c}
              name="sections"
              required
              placeholder="A,B"
              value={sections}
              onChange={(e) => setSections(e.target.value)}
            />
          )}
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="열 수">
            {(c) => (
              <Input
                {...c}
                type="number"
                name="rows"
                min="1"
                required
                value={rows}
                onChange={(e) => setRows(e.target.value)}
              />
            )}
          </Field>
          <Field label="열당 좌석 수">
            {(c) => (
              <Input
                {...c}
                type="number"
                name="perRow"
                min="1"
                required
                value={perRow}
                onChange={(e) => setPerRow(e.target.value)}
              />
            )}
          </Field>
        </div>
        <fieldset className="min-w-0 rounded-card border border-line bg-surface p-4">
          <legend className="px-1 text-caption font-semibold">
            등급별 열 수와 가격
          </legend>
          <div className="grid gap-4">
            {grades.map((grade) => (
              <div key={grade} className="grid gap-4 sm:grid-cols-2">
                <Field label={`${grade} 열 수`}>
                  {(c) => (
                    <Input
                      {...c}
                      type="number"
                      name={grade}
                      min="0"
                      required
                      value={counts[grade]}
                      onChange={(e) =>
                        setCounts({ ...counts, [grade]: e.target.value })
                      }
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
          {gradeError && (
            <InlineError role="alert" className="mt-3">
              {gradeError}
            </InlineError>
          )}
        </fieldset>
        {state.message && (
          <InlineError role="alert">{state.message}</InlineError>
        )}
        <div>
          <Button type="submit" disabled={pending}>
            {pending ? '등록 중' : '등록'}
          </Button>
        </div>
      </div>
      <Card className="grid gap-3 lg:sticky lg:top-6">
        <h2 className="text-heading font-bold">좌석 배치 미리보기</h2>
        <SeatPreview preview={preview} />
      </Card>
    </form>
  );
}
