'use client';

import { useEffect, useState } from 'react';
import { call, codeOf } from './booking-shared';
import { couponError, couponRejectReason } from './error-messages';
import {
  amountSchema,
  myCouponsSchema,
  POLL_INTERVAL_MS,
  POLL_MAX_ATTEMPTS,
  type AmountView,
  type MyCoupon,
} from './coupon-shared';

export function CouponPanel({
  bookingId,
  amount,
  onAmount,
  onSessionExpired,
  disabled,
}: {
  bookingId: number;
  amount: AmountView;
  onAmount: (amount: AmountView) => void;
  onSessionExpired: () => void;
  disabled: boolean;
}) {
  const [coupons, setCoupons] = useState<MyCoupon[]>([]);
  const [selected, setSelected] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  useEffect(() => {
    let alive = true;
    call('/api/me/coupons', 'GET')
      .then((data) => {
        if (!alive) return;
        const now = Date.now();
        setCoupons(
          myCouponsSchema
            .parse(data)
            .filter(
              (coupon) =>
                coupon.status === 'ISSUED' &&
                Date.parse(coupon.definition.validFrom) <= now &&
                now <= Date.parse(coupon.definition.validUntil),
            ),
        );
      })
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, []);
  const fail = (error: unknown) => {
    const code = codeOf(error);
    if (code === 'SESSION_EXPIRED') onSessionExpired();
    setMessage(couponError(code));
  };
  async function apply() {
    if (!selected) return;
    setBusy(true);
    setMessage('');
    try {
      onAmount(
        amountSchema.parse(
          await call(`/api/bookings/${bookingId}/coupon`, 'PUT', {
            issuedCouponId: Number(selected),
          }),
        ),
      );
      for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
        const next = amountSchema.parse(
          await call(`/api/bookings/${bookingId}`, 'GET'),
        );
        onAmount(next);
        if (next.coupon?.state !== 'RESERVING') return;
      }
      setMessage(
        '쿠폰 적용 결과를 아직 받지 못했습니다. 잠시 후 다시 확인해 주세요.',
      );
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  async function remove() {
    setBusy(true);
    setMessage('');
    try {
      onAmount(
        amountSchema.parse(
          await call(`/api/bookings/${bookingId}/coupon`, 'DELETE'),
        ),
      );
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  const coupon = amount.coupon;
  const locked = disabled || busy;
  return (
    <fieldset>
      <legend>쿠폰</legend>
      {coupon?.state === 'RESERVING' && <p>쿠폰 적용 중입니다.</p>}
      {coupon?.state === 'RESERVED' && (
        <p>
          쿠폰 적용됨: -{(coupon.discountAmount ?? 0).toLocaleString('ko-KR')}원
        </p>
      )}
      {coupon?.state === 'REJECTED' && (
        <p role="alert">
          쿠폰을 적용하지 못했습니다. {couponRejectReason(coupon.rejectReason)}{' '}
          정가로 결제할 수 있습니다.
        </p>
      )}
      {coupons.length > 0 ? (
        <>
          <select
            aria-label="쿠폰 선택"
            value={selected}
            disabled={locked}
            onChange={(event) => setSelected(event.target.value)}
          >
            <option value="">쿠폰 선택</option>
            {coupons.map((item) => (
              <option key={item.id} value={item.id}>
                {item.definition.name}
              </option>
            ))}
          </select>
          <button
            type="button"
            disabled={locked || !selected}
            onClick={() => void apply()}
          >
            쿠폰 적용
          </button>
        </>
      ) : (
        <p>사용할 수 있는 쿠폰이 없습니다.</p>
      )}
      {coupon && (
        <button type="button" disabled={locked} onClick={() => void remove()}>
          쿠폰 해제
        </button>
      )}
      {message && <p role="alert">{message}</p>}
    </fieldset>
  );
}
