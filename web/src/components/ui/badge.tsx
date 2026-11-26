import type { HTMLAttributes } from 'react';
import { cx } from '@/lib/cx';

export type BadgeTone = 'neutral' | 'brand' | 'success' | 'warning' | 'danger';

const tones: Record<BadgeTone, string> = {
  neutral: 'bg-canvas text-fg-muted border-line',
  brand: 'bg-brand-soft text-brand-strong border-transparent',
  success: 'bg-success-soft text-success-strong border-transparent',
  warning: 'bg-warning-soft text-warning-strong border-transparent',
  danger: 'bg-danger-soft text-danger-strong border-transparent',
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: BadgeTone;
}

/** 상태 표시. 색만으로 구분하지 않도록 항상 글자를 함께 쓴다. */
export function Badge({ tone = 'neutral', className, ...props }: BadgeProps) {
  return (
    <span
      className={cx(
        'inline-flex items-center gap-1 rounded-pill border px-2 py-0.5 text-micro font-semibold whitespace-nowrap',
        tones[tone],
        className,
      )}
      {...props}
    />
  );
}
