import type { ReactNode } from 'react';
import { CircleAlert, Inbox, LoaderCircle } from 'lucide-react';
import { cx } from '@/lib/cx';

interface StateProps {
  title: ReactNode;
  description?: ReactNode;
  /** 다음 행동(재시도, 이동 등) */
  action?: ReactNode;
  className?: string;
}

function StateLayout({
  icon,
  title,
  description,
  action,
  className,
  role,
}: StateProps & { icon: ReactNode; role?: 'alert' | 'status' }) {
  return (
    <div
      role={role}
      className={cx(
        'flex flex-col items-center gap-3 rounded-card border border-line bg-surface px-6 py-10 text-center',
        className,
      )}
    >
      {icon}
      <div className="grid gap-1">
        <p className="text-body font-semibold text-fg">{title}</p>
        {description ? (
          <p className="text-caption text-fg-muted">{description}</p>
        ) : null}
      </div>
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  );
}

/** 결과가 없을 때. 조회 실패와 구분해서 쓴다. */
export function EmptyState(props: StateProps) {
  return (
    <StateLayout
      {...props}
      icon={<Inbox aria-hidden="true" className="size-8 text-fg-muted" />}
    />
  );
}

/** 조회·처리 실패. 할 수 있는 행동을 action으로 함께 준다. */
export function ErrorState(props: StateProps) {
  return (
    <StateLayout
      {...props}
      role="alert"
      icon={<CircleAlert aria-hidden="true" className="size-8 text-danger" />}
    />
  );
}

/** 화면 일부만 실패했을 때 그 자리에 두는 한 줄 안내. */
export function InlineError({
  children,
  className,
  role,
}: {
  children: ReactNode;
  className?: string;
  role?: 'alert' | 'status';
}) {
  return (
    <p
      role={role}
      className={cx(
        'flex items-center gap-2 rounded-control bg-danger-soft px-3 py-2 text-caption text-danger-strong',
        className,
      )}
    >
      <CircleAlert aria-hidden="true" className="size-4 shrink-0" />
      {children}
    </p>
  );
}

export function LoadingState({
  label = '불러오는 중이에요',
  className,
}: {
  label?: string;
  className?: string;
}) {
  return (
    <div
      role="status"
      className={cx(
        'flex items-center justify-center gap-2 py-10 text-caption text-fg-muted',
        className,
      )}
    >
      <LoaderCircle
        aria-hidden="true"
        className="size-5 animate-spin motion-reduce:animate-none"
      />
      {label}
    </div>
  );
}

/** 자리 표시용 스켈레톤. 크기는 className으로 준다. */
export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      aria-hidden="true"
      className={cx(
        'animate-pulse rounded-control bg-line motion-reduce:animate-none',
        className,
      )}
    />
  );
}
