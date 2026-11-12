import type { Metadata } from 'next';
import type { ReactNode } from 'react';
import { Suspense } from 'react';
import Link from 'next/link';
import { getCurrentUser } from '@/server/auth/current-user';
import { logoutAction } from '@/features/auth/actions';
import './globals.css';

export const metadata: Metadata = {
  title: '티켓 예매',
  description: '공연과 티켓 예매',
};

async function UserMenu() {
  const user = await getCurrentUser();
  return user ? (
    <>
      <span>
        {user.name} ({user.role})
      </span>
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
    <html lang="ko">
      <body>
        <header>
          <Link href="/">티켓 예매</Link>
          <Suspense fallback={null}>
            <UserMenu />
          </Suspense>
        </header>
        {children}
      </body>
    </html>
  );
}
