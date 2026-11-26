import type { MyPageView } from '@/server/aggregate/my-page';
import { Badge } from '@/components/ui/badge';
import { EmptyState, InlineError } from '@/components/ui/feedback';
import { formatSeoul } from '@/lib/datetime';

const statusLabels: Record<string, string> = {
  ISSUED: '사용 가능',
  RESERVED: '예매에 적용 중',
  USED: '사용 완료',
  REVOKED: '회수됨',
  EXPIRED: '기간 만료',
};
export function discountText(definition: {
  discountType: 'FIXED' | 'RATE';
  discountValue: number;
}) {
  return definition.discountType === 'RATE'
    ? `${definition.discountValue}% 할인`
    : `${definition.discountValue.toLocaleString('ko-KR')}원 할인`;
}
export function MyCoupons({ view }: { view: MyPageView }) {
  return (
    <section className="mt-8 grid gap-3">
      <h2 className="text-heading font-bold text-fg">내 쿠폰</h2>
      {view.couponsStatus === 'unavailable' ? (
        <InlineError>쿠폰을 불러오지 못했습니다</InlineError>
      ) : view.coupons.length === 0 ? (
        <EmptyState title="보유한 쿠폰이 없습니다." />
      ) : (
        <ul className="m-0 grid list-none gap-2 p-0">
          {view.coupons.map((coupon) => (
            <li
              key={coupon.id}
              className="rounded-card border border-line bg-surface p-4"
            >
              {coupon.definition.name} · {discountText(coupon.definition)} ·{' '}
              {formatSeoul(coupon.definition.validUntil)}까지 ·{' '}
              <Badge tone={coupon.status === 'ISSUED' ? 'success' : 'neutral'}>
                {statusLabels[coupon.status] ?? coupon.status}
              </Badge>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
