export type HeaderRole = 'USER' | 'ORGANIZER' | 'ADMIN';

export interface NavLink {
  href: string;
  label: string;
}

const linksByRole: Record<HeaderRole, readonly NavLink[]> = {
  USER: [
    { href: '/coupons', label: '쿠폰' },
    { href: '/my', label: '마이페이지' },
  ],
  ADMIN: [{ href: '/admin/members', label: '관리자' }],
  ORGANIZER: [
    { href: '/organizer', label: '주최자 대시보드' },
    { href: '/organizer/new', label: '공연 등록' },
  ],
};

const roleBadgeLabels: Partial<Record<HeaderRole, string>> = {
  ORGANIZER: '주최자',
  ADMIN: '관리자',
};

export function navLinksFor(role: HeaderRole): readonly NavLink[] {
  return linksByRole[role];
}

export function roleBadgeLabel(role: HeaderRole): string | undefined {
  return roleBadgeLabels[role];
}
