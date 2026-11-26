import Link from 'next/link';
import { Page, PageHeader } from '@/components/layout/page';
import { buttonClassName } from '@/components/ui/button';
import { tableClass, thClass, tdClass } from '@/components/ui/table-styles';
import { formatSeoul } from '@/lib/datetime';
import { listAdminCoupons } from '@/server/api/admin-coupons';
import { withSessionRedirect } from '@/server/auth/require-user';

export default async function Route({
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
    <Page>
      <PageHeader
        title="쿠폰"
        actions={
          <Link href="/admin/coupons/new" className={buttonClassName()}>
            쿠폰 생성
          </Link>
        }
      />
      <table className={tableClass}>
        <thead>
          <tr>
            <th className={thClass}>ID</th>
            <th className={thClass}>이름</th>
            <th className={thClass}>할인</th>
            <th className={thClass}>발급 방식</th>
            <th className={thClass}>기간</th>
            <th className={thClass}>상태</th>
            <th className={thClass}>
              현재 상태별 건수: 발급(미사용)/예약/사용/회수
            </th>
          </tr>
        </thead>
        <tbody>
          {page.items.map((c) => (
            <tr key={c.id}>
              <td className={tdClass}>{c.id}</td>
              <td className={tdClass}>
                <Link href={`/admin/coupons/${c.id}`} className="font-semibold">
                  {c.name}
                </Link>
              </td>
              <td className={tdClass}>
                {c.discountType === 'RATE'
                  ? `${c.discountValue}%`
                  : `${c.discountValue.toLocaleString('ko-KR')}원`}
              </td>
              <td className={tdClass}>{c.issueMode}</td>
              <td className={tdClass}>
                {formatSeoul(c.validFrom)} ~ {formatSeoul(c.validUntil)}
              </td>
              <td className={tdClass}>{c.status}</td>
              <td className={tdClass}>
                {c.stats.issued}/{c.stats.reserved}/{c.stats.used}/
                {c.stats.revoked}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {page.nextCursor !== null && (
        <Link
          href={`/admin/coupons?cursor=${page.nextCursor}`}
          className={buttonClassName({ variant: 'secondary' })}
        >
          다음 페이지
        </Link>
      )}
    </Page>
  );
}
