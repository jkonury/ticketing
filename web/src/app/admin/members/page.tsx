import Link from 'next/link';
import { Page, PageHeader } from '@/components/layout/page';
import { Button, buttonClassName } from '@/components/ui/button';
import { Field } from '@/components/ui/field';
import { Input } from '@/components/ui/input';
import { tableClass, thClass, tdClass } from '@/components/ui/table-styles';
import { formatSeoul } from '@/lib/datetime';
import { listAdminMembers } from '@/server/api/admin';
import { withSessionRedirect } from '@/server/auth/require-user';
import { RoleForm } from '@/features/admin/role-form';

export default async function Route({
  searchParams,
}: {
  searchParams: Promise<{ email?: string; cursor?: string }>;
}) {
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
  if (page.nextCursor !== null) next.set('cursor', String(page.nextCursor));
  return (
    <Page>
      <PageHeader title="회원 관리" />
      <form className="flex flex-wrap items-end gap-3">
        <Field label="이메일" className="w-full max-w-100">
          {(c) => <Input {...c} name="email" defaultValue={email ?? ''} />}
        </Field>
        <Button type="submit">검색</Button>
      </form>
      <table className={tableClass}>
        <thead>
          <tr>
            <th className={thClass}>ID</th>
            <th className={thClass}>이메일</th>
            <th className={thClass}>이름</th>
            <th className={thClass}>가입일</th>
            <th className={thClass}>역할</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((member) => (
            <tr key={member.id}>
              <td className={tdClass}>{member.id}</td>
              <td className={tdClass}>{member.email}</td>
              <td className={tdClass}>{member.name}</td>
              <td className={tdClass}>{formatSeoul(member.createdAt)}</td>
              <td className={tdClass}>
                <RoleForm memberId={member.id} role={member.role} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {page.nextCursor !== null && (
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
