import Link from 'next/link';
import { notFound } from 'next/navigation';
import { ApiError } from '@/lib/problem';
import { formatSeoul } from '@/lib/datetime';
import { instantToSeoulLocal } from '@/features/admin/coupon-input';
import {
  CouponForm,
  GrantForm,
  RevokeForm,
  StopForm,
} from '@/features/admin/coupon-forms';
import { getAdminCoupon, listIssuedCoupons } from '@/server/api/admin-coupons';
import { withSessionRedirect } from '@/server/auth/require-user';

const STATUSES = ['ISSUED', 'RESERVED', 'USED', 'REVOKED', 'EXPIRED'];

export default async function Page({
  params,
  searchParams,
}: {
  params: Promise<{ id: string }>;
  searchParams: Promise<{ status?: string; cursor?: string }>;
}) {
  const id = Number((await params).id);
  if (!Number.isSafeInteger(id) || id <= 0) notFound();
  const query = await searchParams;
  const status = STATUSES.includes(query.status ?? '')
    ? query.status
    : undefined;
  const cursor = query.cursor ? Number(query.cursor) : NaN;
  let coupon, issued;
  try {
    [coupon, issued] = await withSessionRedirect(() =>
      Promise.all([
        getAdminCoupon(id),
        listIssuedCoupons(id, {
          status,
          cursor: Number.isFinite(cursor) ? cursor : undefined,
        }),
      ]),
    );
  } catch (error) {
    if (error instanceof ApiError && error.code === 'COUPON_NOT_FOUND')
      notFound();
    throw error;
  }
  const locked = coupon.issueStartedAt !== null;
  const next = new URLSearchParams();
  if (status) next.set('status', status);
  if (issued.nextCursor !== null) next.set('cursor', String(issued.nextCursor));
  return (
    <main>
      <h1>
        {coupon.name} ({coupon.status})
      </h1>
      <Link href="/admin/coupons">목록</Link>
      <p>
        발급(미사용) {coupon.stats.issued} / 예약 {coupon.stats.reserved} / 사용{' '}
        {coupon.stats.used} / 회수 {coupon.stats.revoked} (현재 상태별 건수,
        누적{' '}
        {coupon.stats.issued +
          coupon.stats.reserved +
          coupon.stats.used +
          coupon.stats.revoked}
        건)
      </p>
      {locked && <p>발급이 시작되어 이름·설명·종료일만 수정할 수 있습니다.</p>}
      <h2>수정</h2>
      <CouponForm
        locked={locked}
        values={{
          id: coupon.id,
          name: coupon.name,
          description: coupon.description ?? '',
          discountType: coupon.discountType,
          discountValue: coupon.discountValue,
          maxDiscount: coupon.maxDiscount,
          minOrderAmount: coupon.minOrderAmount,
          performanceId: coupon.performanceId,
          validFrom: instantToSeoulLocal(coupon.validFrom),
          validUntil: instantToSeoulLocal(coupon.validUntil),
          issueMode: coupon.issueMode,
          totalQuantity: coupon.totalQuantity,
        }}
      />
      {coupon.status === 'ACTIVE' && <StopForm id={coupon.id} />}
      <h2>직접 지급</h2>
      {coupon.status === 'ACTIVE' ? (
        <GrantForm id={coupon.id} />
      ) : (
        <p>중지된 쿠폰은 지급할 수 없습니다.</p>
      )}
      <h2>발급 목록</h2>
      <form>
        <label>
          상태{' '}
          <select name="status" defaultValue={status ?? ''}>
            <option value="">전체</option>
            {STATUSES.map((s) => (
              <option key={s} value={s}>
                {s}
              </option>
            ))}
          </select>
        </label>
        <button>조회</button>
      </form>
      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>회원</th>
            <th>상태</th>
            <th>주문</th>
            <th>할인액</th>
            <th>발급</th>
            <th>사용</th>
            <th>회수</th>
          </tr>
        </thead>
        <tbody>
          {issued.items.map((c) => (
            <tr key={c.id}>
              <td>{c.id}</td>
              <td>{c.memberId}</td>
              <td>{c.status}</td>
              <td>{c.orderId ?? '-'}</td>
              <td>{c.discountAmount ?? '-'}</td>
              <td>{formatSeoul(c.issuedAt)}</td>
              <td>{c.usedAt ? formatSeoul(c.usedAt) : '-'}</td>
              <td>
                {c.status === 'ISSUED' && (
                  <RevokeForm issuedCouponId={c.id} definitionId={id} />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {issued.nextCursor !== null && (
        <Link href={`/admin/coupons/${id}?${next}`}>다음 페이지</Link>
      )}
    </main>
  );
}
