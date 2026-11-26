import { Suspense } from 'react';
import Link from 'next/link';
import { logoutAction } from '@/features/auth/actions';
import { siteConfig } from '@/lib/site';
import { getCurrentUser } from '@/server/auth/current-user';
import { HeaderNav } from './header-nav';

async function HeaderUser() {
  const user = await getCurrentUser();
  return (
    <HeaderNav
      user={user ? { name: user.name, role: user.role } : null}
      logoutAction={logoutAction}
    />
  );
}

export function SiteHeader() {
  return (
    <header className="sticky top-0 z-30 border-b border-line bg-surface">
      <div className="mx-auto flex h-14 max-w-280 items-center justify-between gap-4 px-4 md:h-16 md:px-6">
        <Link
          href="/"
          aria-label={`${siteConfig.name} 홈`}
          className="flex items-baseline gap-2 no-underline hover:no-underline"
        >
          <span className="text-heading font-extrabold text-brand">
            {siteConfig.name}
          </span>
          <span className="text-micro font-semibold tracking-widest text-fg-muted">
            {siteConfig.englishName}
          </span>
        </Link>
        <Suspense fallback={<div className="h-11 w-24" aria-hidden="true" />}>
          <HeaderUser />
        </Suspense>
      </div>
    </header>
  );
}
