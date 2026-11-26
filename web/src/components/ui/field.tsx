import { useId, type ReactNode } from 'react';
import { cx } from '@/lib/cx';

export interface FieldControlProps {
  id: string;
  'aria-describedby'?: string;
  'aria-invalid'?: true;
  required?: boolean;
}

export interface FieldProps {
  label: ReactNode;
  /** 입력 전에 알려야 하는 조건. 오류가 있어도 함께 표시한다. */
  hint?: ReactNode;
  error?: string;
  required?: boolean;
  className?: string;
  children: (control: FieldControlProps) => ReactNode;
}

/** 레이블, 입력, 도움말, 오류를 묶고 접근성 속성을 입력에 연결한다. */
export function Field({
  label,
  hint,
  error,
  required,
  className,
  children,
}: FieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [hintId, errorId].filter(Boolean).join(' ') || undefined;

  return (
    <div className={cx('grid content-start gap-1.5', className)}>
      <label htmlFor={id} className="text-caption font-semibold text-fg">
        {label}
        {required ? (
          <span className="text-danger" aria-hidden="true">
            {' '}
            *
          </span>
        ) : null}
      </label>
      {children({
        id,
        'aria-describedby': describedBy,
        'aria-invalid': error ? true : undefined,
        required,
      })}
      {hint ? (
        <p id={hintId} className="text-caption text-fg-muted">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} className="text-caption font-medium text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
