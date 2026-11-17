import type { ReactNode } from 'react';
import Link from 'next/link';
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
        <main>
          <h1>접근 권한이 없습니다 (403)</h1>
        </main>
      );
    throw error;
  }
  return (
    <>
      <nav aria-label="관리자 메뉴">
        <Link href="/admin/members">회원</Link>{' '}
        <Link href="/admin/coupons">쿠폰</Link>{' '}
        <Link href="/admin/audit-logs">감사 로그</Link>
      </nav>
      {children}
    </>
  );
}
