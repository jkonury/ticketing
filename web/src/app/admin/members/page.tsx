import Link from 'next/link';
import { formatSeoul } from '@/lib/datetime';
import { listAdminMembers } from '@/server/api/admin';
import { withSessionRedirect } from '@/server/auth/require-user';
import { RoleForm } from '@/features/admin/role-form';

export default async function Page({
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
    <main>
      <h1>회원 관리</h1>
      <form>
        <label>
          이메일 <input name="email" defaultValue={email ?? ''} />
        </label>
        <button>검색</button>
      </form>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>이메일</th>
            <th>이름</th>
            <th>가입일</th>
            <th>역할</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((member) => (
            <tr key={member.id}>
              <td>{member.id}</td>
              <td>{member.email}</td>
              <td>{member.name}</td>
              <td>{formatSeoul(member.createdAt)}</td>
              <td>
                <RoleForm memberId={member.id} role={member.role} />
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {page.nextCursor !== null && (
        <Link href={`/admin/members?${next}`}>다음 페이지</Link>
      )}
    </main>
  );
}
