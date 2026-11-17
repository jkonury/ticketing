'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';
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
  if (coupons.length === 0) return <p>받을 수 있는 쿠폰이 없습니다.</p>;
  return (
    <>
      {message && <p role="alert">{message}</p>}
      <ul>
        {coupons.map((coupon) => {
          const done = coupon.claimed || claimed.includes(coupon.id);
          return (
            <li key={coupon.id}>
              <strong>{coupon.name}</strong>{' '}
              {coupon.discountType === 'RATE'
                ? `${coupon.discountValue}% 할인`
                : `${coupon.discountValue.toLocaleString('ko-KR')}원 할인`}
              {coupon.description && <p>{coupon.description}</p>}{' '}
              <button
                type="button"
                disabled={done || pending === coupon.id}
                onClick={() => void claim(coupon.id)}
              >
                {done ? '받음' : '받기'}
              </button>
            </li>
          );
        })}
      </ul>
    </>
  );
}
