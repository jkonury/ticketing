import type { ButtonHTMLAttributes } from 'react';
import { LoaderCircle } from 'lucide-react';
import { cx } from '@/lib/cx';

export type ButtonVariant = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'md' | 'icon';

const base =
  'inline-flex min-h-11 items-center justify-center gap-2 rounded-control border font-semibold text-body no-underline transition-colors ' +
  'cursor-pointer focus-visible:outline-3 focus-visible:outline-offset-2 focus-visible:outline-brand ' +
  'disabled:cursor-not-allowed disabled:border-transparent disabled:bg-disabled disabled:text-on-disabled ' +
  'hover:no-underline';

const variants: Record<ButtonVariant, string> = {
  primary: 'border-transparent bg-brand text-on-brand hover:bg-brand-strong',
  secondary: 'border-line-strong bg-surface text-fg hover:bg-canvas',
  ghost: 'border-transparent bg-transparent text-brand hover:bg-brand-soft',
  danger: 'border-transparent bg-danger text-white hover:bg-danger-strong',
};

const sizes: Record<ButtonSize, string> = {
  md: 'px-5 py-2.5',
  icon: 'size-11 p-0',
};

export interface ButtonStyleOptions {
  variant?: ButtonVariant;
  size?: ButtonSize;
  fullWidth?: boolean;
}

/** Link처럼 button이 아닌 요소를 버튼 모양으로 보일 때 쓴다. */
export function buttonClassName({
  variant = 'primary',
  size = 'md',
  fullWidth = false,
}: ButtonStyleOptions = {}): string {
  return cx(base, variants[variant], sizes[size], fullWidth && 'w-full');
}

export interface ButtonProps
  extends ButtonHTMLAttributes<HTMLButtonElement>, ButtonStyleOptions {
  /** 요청 중 표시. 버튼을 비활성화하고 진행 표시를 붙인다. */
  loading?: boolean;
}

export function Button({
  variant,
  size,
  fullWidth,
  loading = false,
  disabled,
  type = 'button',
  className,
  children,
  ...props
}: ButtonProps) {
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={cx(buttonClassName({ variant, size, fullWidth }), className)}
      {...props}
    >
      {loading ? (
        <LoaderCircle
          aria-hidden="true"
          className="size-4 animate-spin motion-reduce:animate-none"
        />
      ) : null}
      {children}
    </button>
  );
}
