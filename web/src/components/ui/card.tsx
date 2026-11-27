import type { ComponentPropsWithRef } from 'react';
import { cx } from '@/lib/cx';

export interface CardProps extends ComponentPropsWithRef<'div'> {
  /** 포스터처럼 가장자리까지 채우는 내용은 none을 쓴다. */
  padding?: 'none' | 'md' | 'lg';
}

const paddings = { none: '', md: 'p-4', lg: 'p-6' } as const;

export function Card({ padding = 'md', className, ...props }: CardProps) {
  return (
    <div
      className={cx(
        'overflow-hidden rounded-card border border-line bg-surface',
        paddings[padding],
        className,
      )}
      {...props}
    />
  );
}
