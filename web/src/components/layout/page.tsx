import type { ReactNode } from 'react';
import { cx } from '@/lib/cx';

/** 헤더와 같은 가로 기준(max-w-280, px-4/md:px-6)을 쓰는 페이지 컨테이너. */
export function Page({
  narrow = false,
  className,
  children,
}: {
  /** 로그인·가입처럼 좁은 폼 화면 */
  narrow?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <main
      className={cx(
        'mx-auto w-full px-4 py-8 md:px-6 md:py-10',
        narrow ? 'max-w-110' : 'max-w-280',
        className,
      )}
    >
      {children}
    </main>
  );
}

export function PageHeader({
  title,
  description,
  actions,
  className,
}: {
  title: ReactNode;
  description?: ReactNode;
  actions?: ReactNode;
  className?: string;
}) {
  return (
    <div
      className={cx(
        'mb-6 flex flex-wrap items-start justify-between gap-4',
        className,
      )}
    >
      <div className="grid gap-2">
        <h1 className="text-title font-extrabold text-fg">{title}</h1>
        {description ? (
          <p className="text-body text-fg-muted">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex gap-2">{actions}</div> : null}
    </div>
  );
}
