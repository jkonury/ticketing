import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Suspense } from 'react';
import Link from 'next/link';
import { getCurrentUser } from '@/server/auth/current-user';
import { logoutAction } from '@/features/auth/actions';
import { pretendard } from './fonts/pretendard';
import './globals.css';

export const metadata: Metadata = {
  title: '티켓 예매',
  description: '공연과 티켓 예매',
};

async function UserMenu() {
  const user = await getCurrentUser();
  return user ? (
    <>
      <span className="user-name">
        {user.name} ({user.role})
      </span>
      {user.role === 'USER' ? (
        <>
          <Link href="/coupons">쿠폰</Link>
          <Link href="/my">마이페이지</Link>
        </>
      ) : user.role === 'ADMIN' ? (
        <Link href="/admin/members">관리자</Link>
      ) : (
        <>
          <Link href="/organizer">주최자 대시보드</Link>
          <Link href="/organizer/new">공연 등록</Link>
        </>
      )}
      <form action={logoutAction}>
        <button>로그아웃</button>
      </form>
    </>
  ) : (
    <Link href="/login">로그인</Link>
  );
}

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="ko" className={pretendard.variable}>
      <body>
        <header className="site-header">
          <div className="header-inner">
            <Link className="brand" href="/">
              티켓 예매
            </Link>
            <nav className="user-menu" aria-label="사용자 메뉴">
              <Suspense fallback={null}>
                <UserMenu />
              </Suspense>
            </nav>
          </div>
        </header>
        {children}
      </body>
    </html>
  );
}
