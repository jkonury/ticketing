import type {
  InputHTMLAttributes,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from 'react';
import { cx } from '@/lib/cx';

const control =
  'w-full rounded-control border border-line-strong bg-surface px-3 text-body text-fg ' +
  'placeholder:text-fg-muted focus-visible:outline-3 focus-visible:outline-offset-0 focus-visible:outline-brand ' +
  'disabled:cursor-not-allowed disabled:bg-disabled disabled:text-on-disabled ' +
  'aria-invalid:border-danger aria-invalid:border-2';

export function Input({
  className,
  ...props
}: InputHTMLAttributes<HTMLInputElement>) {
  return (
    <input className={cx(control, 'min-h-11 py-2', className)} {...props} />
  );
}

export function Textarea({
  className,
  ...props
}: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      className={cx(control, 'min-h-25 py-2.5', className)}
      {...props}
    />
  );
}

export function Select({
  className,
  ...props
}: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <select className={cx(control, 'min-h-11 py-2', className)} {...props} />
  );
}
