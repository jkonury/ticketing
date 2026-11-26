'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { EmptyState, InlineError } from '@/components/ui/feedback';
import { call, codeOf } from '@/features/booking/booking-shared';
import { couponError } from '@/features/booking/error-messages';

export type ClaimableCoupon = {
  id: number;
  name: string;
  description: string | null;
  discountType: 'FIXED' | 'RATE';
  discountValue: number;
  minOrderAmount: number;
  validUntil: string;
  claimed: boolean;
};
export function ClaimList({ coupons }: { coupons: ClaimableCoupon[] }) {
  const router = useRouter();
  const [claimed, setClaimed] = useState<number[]>([]);
  const [pending, setPending] = useState<number | null>(null);
  const [message, setMessage] = useState('');
  async function claim(id: number) {
    setPending(id);
    setMessage('');
    try {
      await call(`/api/coupons/${id}/claims`, 'POST');
      setClaimed((ids) => [...ids, id]);
    } catch (error) {
      const code = codeOf(error);
      if (code === 'SESSION_EXPIRED') router.push('/login');
      if (code === 'COUPON_ALREADY_CLAIMED') setClaimed((ids) => [...ids, id]);
      setMessage(couponError(code));
    } finally {
      setPending(null);
    }
  }
  if (coupons.length === 0)
    return <EmptyState title="받을 수 있는 쿠폰이 없습니다." />;
  return (
    <>
      {message && (
        <InlineError role="alert" className="mb-3">
          {message}
        </InlineError>
      )}
      <ul className="m-0 grid list-none gap-3 p-0">
        {coupons.map((coupon) => {
          const done = coupon.claimed || claimed.includes(coupon.id);
          return (
            <li
              key={coupon.id}
              className="flex flex-wrap items-center justify-between gap-3 rounded-card border border-line bg-surface p-4"
            >
              <div className="grid gap-1">
                <strong>{coupon.name}</strong>{' '}
                {coupon.discountType === 'RATE'
                  ? `${coupon.discountValue}% 할인`
                  : `${coupon.discountValue.toLocaleString('ko-KR')}원 할인`}
                {coupon.description && (
                  <p className="text-caption text-fg-muted">
                    {coupon.description}
                  </p>
                )}
              </div>
              <Button
                variant={done ? 'secondary' : 'primary'}
                disabled={done || pending === coupon.id}
                onClick={() => void claim(coupon.id)}
              >
                {done ? '받음' : '받기'}
              </Button>
            </li>
          );
        })}
      </ul>
    </>
  );
}
