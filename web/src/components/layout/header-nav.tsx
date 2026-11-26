'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { Menu } from 'lucide-react';
import { Badge } from '@/components/ui/badge';
import { Button, buttonClassName } from '@/components/ui/button';
import {
  Sheet,
  SheetClose,
  SheetContent,
  SheetTrigger,
} from '@/components/ui/sheet';
import { cx } from '@/lib/cx';
import { navLinksFor, roleBadgeLabel, type HeaderRole } from './nav-links';

export interface HeaderNavUser {
  name: string;
  role: HeaderRole;
}

export interface HeaderNavProps {
  user: HeaderNavUser | null;
  /** 로그아웃 form action. 서버 액션을 서버 컴포넌트에서 받는다. */
  logoutAction: () => void | Promise<void>;
}

function UserName({ user }: { user: HeaderNavUser }) {
  const badge = roleBadgeLabel(user.role);
  return (
    <span className="flex items-center gap-2 text-body font-semibold text-fg">
      {user.name}
      {badge ? <Badge tone="brand">{badge}</Badge> : null}
    </span>
  );
}

function linkClassName(active: boolean, extra?: string) {
  return cx(
    'inline-flex min-h-11 items-center rounded-control px-3 text-body font-medium no-underline hover:bg-canvas hover:no-underline',
    active ? 'text-brand' : 'text-fg',
    extra,
  );
}

/** 로그인 상태에 따라 헤더 오른쪽 영역을 그린다. 데스크톱은 인라인, 모바일은 시트 메뉴. */
export function HeaderNav({ user, logoutAction }: HeaderNavProps) {
  const pathname = usePathname();

  if (!user) {
    return (
      <Link href="/login" className={buttonClassName({ variant: 'secondary' })}>
        로그인
      </Link>
    );
  }

  const links = navLinksFor(user.role);
  const current = (href: string) => (pathname === href ? 'page' : undefined);

  return (
    <>
      <nav
        aria-label="사용자 메뉴"
        className="hidden items-center gap-1 md:flex"
      >
        {links.map((link) => (
          <Link
            key={link.href}
            href={link.href}
            aria-current={current(link.href)}
            className={linkClassName(pathname === link.href)}
          >
            {link.label}
          </Link>
        ))}
        <span className="mx-2">
          <UserName user={user} />
        </span>
        <form action={logoutAction}>
          <Button type="submit" variant="secondary">
            로그아웃
          </Button>
        </form>
      </nav>
      <div className="md:hidden">
        <Sheet>
          <SheetTrigger asChild>
            <Button variant="ghost" size="icon" aria-label="메뉴 열기">
              <Menu aria-hidden="true" className="size-6" />
            </Button>
          </SheetTrigger>
          <SheetContent title="메뉴" side="right">
            <UserName user={user} />
            <nav aria-label="모바일 메뉴" className="flex flex-col gap-1">
              {links.map((link) => (
                <SheetClose asChild key={link.href}>
                  <Link
                    href={link.href}
                    aria-current={current(link.href)}
                    className={linkClassName(pathname === link.href, 'w-full')}
                  >
                    {link.label}
                  </Link>
                </SheetClose>
              ))}
            </nav>
            <form action={logoutAction}>
              <Button type="submit" variant="secondary" fullWidth>
                로그아웃
              </Button>
            </form>
          </SheetContent>
        </Sheet>
      </div>
    </>
  );
}
