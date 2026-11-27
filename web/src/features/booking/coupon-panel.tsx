'use client';

import { useCallback, useRef, useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  EmptyState,
  ErrorState,
  InlineError,
  Skeleton,
} from '@/components/ui/feedback';
import { Select } from '@/components/ui/input';
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
  const pollGeneration = useRef(0);
  const [loadState, setLoadState] = useState<'loading' | 'ready' | 'error'>(
    'loading',
  );
  const couponLoad = useRef<AbortController | null>(null);
  const loadCoupons = useCallback(async (signal: AbortSignal) => {
    if (signal.aborted) return;
    try {
      const data = await call('/api/me/coupons', 'GET');
      if (signal.aborted) return;
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
      setLoadState('ready');
    } catch {
      if (!signal.aborted) setLoadState('error');
    }
  }, []);
  const couponPanelRef = useCallback(
    (element: HTMLFieldSetElement | null) => {
      if (!element) return;
      const controller = new AbortController();
      couponLoad.current = controller;
      queueMicrotask(() => void loadCoupons(controller.signal));
      return () => controller.abort();
    },
    [loadCoupons],
  );
  const fail = (error: unknown) => {
    const code = codeOf(error);
    if (code === 'SESSION_EXPIRED') onSessionExpired();
    setMessage(couponError(code));
  };
  async function pollResult(generation: number) {
    try {
      for (let attempt = 0; attempt < POLL_MAX_ATTEMPTS; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
        if (pollGeneration.current !== generation) return;
        const next = amountSchema.parse(
          await call(`/api/bookings/${bookingId}`, 'GET'),
        );
        if (pollGeneration.current !== generation) return;
        onAmount(next);
        if (next.coupon?.state !== 'RESERVING') return;
      }
      setMessage(
        '쿠폰 적용 결과를 기다리고 있어요. 쿠폰을 해제하고 정가로 결제할 수 있어요.',
      );
    } catch (error) {
      if (pollGeneration.current === generation) fail(error);
    }
  }
  async function apply() {
    if (!selected) return;
    setBusy(true);
    setMessage('');
    try {
      const next = amountSchema.parse(
        await call(`/api/bookings/${bookingId}/coupon`, 'PUT', {
          issuedCouponId: Number(selected),
        }),
      );
      onAmount(next);
      if (next.coupon?.state === 'RESERVING') {
        void pollResult(++pollGeneration.current);
      }
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
      const next = amountSchema.parse(
        await call(`/api/bookings/${bookingId}/coupon`, 'DELETE'),
      );
      pollGeneration.current++;
      onAmount(next);
      setMessage('');
    } catch (error) {
      fail(error);
    } finally {
      setBusy(false);
    }
  }
  const coupon = amount.coupon;
  const locked = disabled || busy;
  const applyLocked = locked || coupon?.state === 'RESERVING';
  return (
    <fieldset
      ref={couponPanelRef}
      className="grid min-w-0 gap-3 rounded-control border border-line p-4"
    >
      <legend className="px-1 text-body font-semibold text-fg">쿠폰</legend>
      {coupon?.state === 'RESERVING' && <p>쿠폰을 적용하고 있어요.</p>}
      {coupon?.state === 'RESERVED' && (
        <p>
          쿠폰 적용됨: -{(coupon.discountAmount ?? 0).toLocaleString('ko-KR')}원
        </p>
      )}
      {coupon?.state === 'REJECTED' && (
        <InlineError role="alert">
          쿠폰을 적용하지 못했어요. {couponRejectReason(coupon.rejectReason)}{' '}
          정가로 결제할 수 있어요.
        </InlineError>
      )}
      {loadState === 'loading' ? (
        <div role="status" aria-label="쿠폰을 불러오는 중이에요">
          <Skeleton className="h-11 w-full" />
        </div>
      ) : loadState === 'error' ? (
        <ErrorState
          className="py-6"
          title="쿠폰을 불러오지 못했어요"
          description="쿠폰 없이도 결제할 수 있어요. 쿠폰을 쓰려면 다시 시도해 주세요."
          action={
            <Button
              variant="secondary"
              onClick={() => {
                setLoadState('loading');
                if (couponLoad.current)
                  void loadCoupons(couponLoad.current.signal);
              }}
            >
              다시 시도
            </Button>
          }
        />
      ) : coupons.length > 0 ? (
        <div className="flex flex-wrap gap-2">
          <Select
            className="min-w-0 flex-1"
            aria-label="쿠폰 선택"
            value={selected}
            disabled={applyLocked}
            onChange={(event) => setSelected(event.target.value)}
          >
            <option value="">쿠폰 선택</option>
            {coupons.map((item) => (
              <option key={item.id} value={item.id}>
                {item.definition.name}
              </option>
            ))}
          </Select>
          <Button
            variant="secondary"
            disabled={applyLocked || !selected}
            onClick={() => void apply()}
          >
            쿠폰 적용
          </Button>
        </div>
      ) : (
        <EmptyState
          className="py-6"
          title="사용할 수 있는 쿠폰이 없어요"
          description="정가로 결제할 수 있어요."
        />
      )}
      {coupon && (
        <div>
          <Button
            variant="secondary"
            disabled={locked}
            onClick={() => void remove()}
          >
            쿠폰 해제
          </Button>
        </div>
      )}
      {message && <InlineError role="alert">{message}</InlineError>}
    </fieldset>
  );
}
