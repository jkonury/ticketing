import Link from 'next/link';
import { notFound } from 'next/navigation';
import { Page, PageHeader } from '@/components/layout/page';
import { Button, buttonClassName } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Field } from '@/components/ui/field';
import { Select } from '@/components/ui/input';
import { tableClass, thClass, tdClass } from '@/components/ui/table-styles';
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

export default async function Route({
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
    <Page>
      <PageHeader
        title={`${coupon.name} (${coupon.status})`}
        actions={
          <Link
            href="/admin/coupons"
            className={buttonClassName({ variant: 'secondary' })}
          >
            목록
          </Link>
        }
      />
      <p className="text-body text-fg-muted">
        발급(미사용) {coupon.stats.issued} / 예약 {coupon.stats.reserved} / 사용{' '}
        {coupon.stats.used} / 회수 {coupon.stats.revoked} (현재 상태별 건수,
        누적{' '}
        {coupon.stats.issued +
          coupon.stats.reserved +
          coupon.stats.used +
          coupon.stats.revoked}
        건)
      </p>
      {locked && (
        <p className="mt-2 text-body text-fg-muted">
          발급이 시작되어 이름·설명·종료일만 수정할 수 있습니다.
        </p>
      )}
      <h2 className="mb-4 mt-8 text-heading font-bold">수정</h2>
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
      <h2 className="mb-4 mt-8 text-heading font-bold">직접 지급</h2>
      {coupon.status === 'ACTIVE' ? (
        <GrantForm id={coupon.id} />
      ) : (
        <p className="text-body text-fg-muted">
          중지된 쿠폰은 지급할 수 없습니다.
        </p>
      )}
      <h2 className="mb-4 mt-8 text-heading font-bold">발급 목록</h2>
      <form className="flex flex-wrap items-end gap-3">
        <Field label="상태" className="w-full max-w-60">
          {(c) => (
            <Select {...c} name="status" defaultValue={status ?? ''}>
              <option value="">전체</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </Select>
          )}
        </Field>
        <Button type="submit">조회</Button>
      </form>
      <table className={tableClass}>
        <thead>
          <tr>
            <th className={thClass}>ID</th>
            <th className={thClass}>회원</th>
            <th className={thClass}>상태</th>
            <th className={thClass}>주문</th>
            <th className={thClass}>할인액</th>
            <th className={thClass}>발급</th>
            <th className={thClass}>사용</th>
            <th className={thClass}>회수</th>
          </tr>
        </thead>
        <tbody>
          {issued.items.map((c) => (
            <tr key={c.id}>
              <td className={tdClass}>{c.id}</td>
              <td className={tdClass}>{c.memberId}</td>
              <td className={tdClass}>{c.status}</td>
              <td className={tdClass}>{c.orderId ?? '-'}</td>
              <td className={tdClass}>{c.discountAmount ?? '-'}</td>
              <td className={tdClass}>{formatSeoul(c.issuedAt)}</td>
              <td className={tdClass}>
                {c.usedAt ? formatSeoul(c.usedAt) : '-'}
              </td>
              <td className={tdClass}>
                {c.status === 'ISSUED' && (
                  <RevokeForm issuedCouponId={c.id} definitionId={id} />
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      {issued.nextCursor !== null && (
        <Link
          href={`/admin/coupons/${id}?${next}`}
          className={buttonClassName({ variant: 'secondary' })}
        >
          다음 페이지
        </Link>
      )}
    </Page>
  );
}
