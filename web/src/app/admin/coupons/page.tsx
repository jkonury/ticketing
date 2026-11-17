import Link from 'next/link';
import { formatSeoul } from '@/lib/datetime';
import { listAdminCoupons } from '@/server/api/admin-coupons';
import { withSessionRedirect } from '@/server/auth/require-user';

export default async function Page({
  searchParams,
}: {
  searchParams: Promise<{ cursor?: string }>;
}) {
  const { cursor } = await searchParams;
  const parsed = cursor ? Number(cursor) : NaN;
  const page = await withSessionRedirect(() =>
    listAdminCoupons({ cursor: Number.isFinite(parsed) ? parsed : undefined }),
  );
  return (
    <main>
      <h1>쿠폰</h1>
      <Link href="/admin/coupons/new">쿠폰 생성</Link>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>이름</th>
            <th>할인</th>
            <th>발급 방식</th>
            <th>기간</th>
            <th>상태</th>
            <th>현재 상태별 건수: 발급(미사용)/예약/사용/회수</th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((c) => (
            <tr key={c.id}>
              <td>{c.id}</td>
              <td>
                <Link href={`/admin/coupons/${c.id}`}>{c.name}</Link>
              </td>
              <td>
                {c.discountType === 'RATE'
                  ? `${c.discountValue}%`
                  : `${c.discountValue.toLocaleString('ko-KR')}원`}
              </td>
              <td>{c.issueMode}</td>
              <td>
                {formatSeoul(c.validFrom)} ~ {formatSeoul(c.validUntil)}
              </td>
              <td>{c.status}</td>
              <td>
                {c.stats.issued}/{c.stats.reserved}/{c.stats.used}/
                {c.stats.revoked}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {page.nextCursor !== null && (
        <Link href={`/admin/coupons?cursor=${page.nextCursor}`}>
          다음 페이지
        </Link>
      )}
    </main>
  );
}
