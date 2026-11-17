import type { MyPageView } from '@/server/aggregate/my-page';
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
    <section>
      <h2>내 쿠폰</h2>
      {view.couponsStatus === 'unavailable' ? (
        <p>쿠폰을 불러오지 못했습니다</p>
      ) : view.coupons.length === 0 ? (
        <p>보유한 쿠폰이 없습니다.</p>
      ) : (
        <ul>
          {view.coupons.map((coupon) => (
            <li key={coupon.id}>
              {coupon.definition.name} · {discountText(coupon.definition)} ·{' '}
              {formatSeoul(coupon.definition.validUntil)}까지 ·{' '}
              {statusLabels[coupon.status] ?? coupon.status}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}
