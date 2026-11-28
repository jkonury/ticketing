import { Suspense } from 'react';
import Link from 'next/link';
import { RouteLoading } from '@/components/route-loading';
import { Page, PageHeader } from '@/components/layout/page';
import { Badge } from '@/components/ui/badge';
import { Button, buttonClassName } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import {
  tableClass,
  thClass,
  tdClass,
  secondaryCol,
} from '@/components/ui/table-styles';
import { formatSeoul } from '@/lib/datetime';
import { listAdminMembers } from '@/server/api/admin';
import { withSessionRedirect } from '@/server/auth/require-user';
import { RoleForm } from '@/features/admin/role-form';
import { SuspensionForm } from '@/features/admin/suspension-form';

type SearchParams = Promise<{ email?: string; cursor?: string }>;

export default function Route({
  searchParams,
}: {
  searchParams: SearchParams;
}) {
  return (
    <Suspense fallback={<RouteLoading />}>
      <Members searchParams={searchParams} />
    </Suspense>
  );
}

async function Members({ searchParams }: { searchParams: SearchParams }) {
  const { email, cursor } = await searchParams;
  const cursorNumber = cursor ? Number(cursor) : undefined;
  const page = await withSessionRedirect(() =>
    listAdminMembers({
      email: email || undefined,
      cursor: Number.isFinite(cursorNumber) ? cursorNumber : undefined,
    }),
  );
  const next = new URLSearchParams();
  if (email) next.set('email', email);
  if (page.nextCursor != null) next.set('cursor', String(page.nextCursor));
  return (
    <Page>
      <PageHeader title="회원 관리" />
      <form className="flex flex-wrap items-end gap-3">
        <Field label="이메일" className="w-full max-w-100">
          {(c) => <Input {...c} name="email" defaultValue={email ?? ''} />}
        </Field>
        <Button type="submit">검색</Button>
      </form>
      <table className={tableClass} aria-label="회원 목록">
        <thead>
          <tr>
            <th className={`${thClass} ${secondaryCol}`}>ID</th>
            <th className={thClass}>이메일</th>
            <th className={`${thClass} ${secondaryCol}`}>이름</th>
            <th className={`${thClass} ${secondaryCol}`}>가입일</th>
            <th className={thClass}>역할</th>
            <th className={thClass}>이용 상태</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((member) => (
            <tr key={member.id}>
              <td className={`${tdClass} ${secondaryCol}`}>{member.id}</td>
              <td className={`${tdClass} break-all`}>{member.email}</td>
              <td className={`${tdClass} ${secondaryCol}`}>{member.name}</td>
              <td className={`${tdClass} ${secondaryCol}`}>
                {formatSeoul(member.createdAt)}
              </td>
              <td className={tdClass}>
                <RoleForm memberId={member.id} role={member.role} />
              </td>
              <td className={tdClass}>
                <div className="flex flex-wrap items-center gap-2">
                  <Badge
                    tone={member.status === 'SUSPENDED' ? 'danger' : 'success'}
                  >
                    {member.status === 'SUSPENDED' ? '이용 정지' : '이용 가능'}
                  </Badge>
                  {(member.role !== 'ADMIN' ||
                    member.status === 'SUSPENDED') && (
                    <SuspensionForm
                      key={member.status}
                      id={member.id}
                      target="member"
                      name={member.email}
                      suspended={member.status === 'SUSPENDED'}
                    />
                  )}
                </div>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {page.nextCursor != null && (
        <Link
          href={`/admin/members?${next}`}
          className={buttonClassName({ variant: 'secondary' })}
        >
          다음 페이지
        </Link>
      )}
    </Page>
  );
}
