import type { ReactNode } from 'react';
import { buttonClassName } from '@/components/ui/button';
import Link from 'next/link';
import { Page, PageHeader } from '@/components/layout/page';
import { ForbiddenError, requireAdmin } from '@/server/auth/require-admin';

export default async function AdminLayout({
  children,
}: {
  children: ReactNode;
}) {
  try {
    await requireAdmin();
  } catch (error) {
    if (error instanceof ForbiddenError)
      return (
        <Page>
          <PageHeader title="접근 권한이 없습니다 (403)" />
        </Page>
      );
    throw error;
  }
  return (
    <>
      <nav
        aria-label="관리자 메뉴"
        className="mx-auto flex w-full max-w-280 flex-wrap gap-2 px-4 pt-6 md:px-6"
      >
        <Link
          href="/admin/members"
          className={buttonClassName({ variant: 'secondary' })}
        >
          회원
        </Link>
        <Link
          href="/admin/performances"
          className={buttonClassName({ variant: 'secondary' })}
        >
          공연
        </Link>
        <Link
          href="/admin/bookings"
          className={buttonClassName({ variant: 'secondary' })}
        >
          예매
        </Link>
        <Link
          href="/admin/coupons"
          className={buttonClassName({ variant: 'secondary' })}
        >
          쿠폰
        </Link>
        <Link
          href="/admin/audit-logs"
          className={buttonClassName({ variant: 'secondary' })}
        >
          감사 로그
        </Link>
      </nav>
      {children}
    </>
  );
}
