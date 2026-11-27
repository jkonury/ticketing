'use client';

import { useCallback, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { Dialog, DialogClose, DialogContent } from '@/components/ui/dialog';
import { cx } from '@/lib/cx';
import { InlineError } from '@/components/ui/feedback';
import { call, codeOf, type Booking } from './booking-shared';
import { bookingError } from './error-messages';
import { CouponPanel } from './coupon-panel';
import type { AmountView } from './coupon-shared';

const WARNING_SECONDS = 60;

export function formatRemaining(seconds: number): string {
  const mm = String(Math.floor(seconds / 60)).padStart(2, '0');
  const ss = String(seconds % 60).padStart(2, '0');
  return `${mm}:${ss}`;
}

export function Payment({
  booking,
  onDone,
  onReset,
}: {
  booking: Booking;
  onDone: () => void;
  onReset: () => void;
}) {
  const holdDuration =
    Date.parse(booking.expiresAt) - Date.parse(booking.serverTime);
  const { receivedAt } = booking;
  const remainingNow = useCallback(
    () =>
      Math.max(
        0,
        Math.ceil((holdDuration - (performance.now() - receivedAt)) / 1000),
      ),
    [holdDuration, receivedAt],
  );
  const [remaining, setRemaining] = useState(remainingNow);
  // 진행 바의 전체 길이는 최초 렌더 시점의 남은 시간이다.
  const [total] = useState(remaining);
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState('');
  const [amount, setAmount] = useState<AmountView>({});
  const paymentKey = useRef(crypto.randomUUID());
  const router = useRouter();
  const countdownRef = useCallback(
    (element: HTMLElement | null) => {
      if (!element) return;
      const timer = setInterval(() => {
        const next = remainingNow();
        setRemaining(next);
        if (next === 0) clearInterval(timer);
      }, 1000);
      return () => clearInterval(timer);
    },
    [remainingNow],
  );
  const expired = remaining === 0;
  const warning = !expired && remaining <= WARNING_SECONDS;
  // 60초 경고는 한 번만 알린다. 문구는 한 번 나타나면 그대로 둔다.
  const [warned, setWarned] = useState(false);
  if (warning && !warned) setWarned(true);
  const handleFailure = (error: unknown) => {
    const code = codeOf(error);
    if (code === 'SESSION_EXPIRED') router.push('/login');
    if (
      code === 'ADMISSION_TOKEN_INVALID' ||
      code === 'ADMISSION_TOKEN_REQUIRED'
    ) {
      onReset();
      return;
    }
    setNotice(bookingError(code));
  };
  async function pay() {
    setPending(true);
    setNotice('');
    try {
      await call(`/api/bookings/${booking.bookingId}/payments`, 'POST', {
        idempotencyKey: paymentKey.current,
      });
      for (let attempt = 0; attempt < 30; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 1000));
        const result = z
          .object({ status: z.string() })
          .parse(await call(`/api/bookings/${booking.bookingId}`, 'GET'));
        if (result.status === 'CONFIRMED') {
          onDone();
          return;
        }
        if (result.status === 'EXPIRED' || result.status === 'CANCELLED') {
          setNotice(
            '결제가 완료되지 않았어요. 결제에 실패했거나 예매 시간이 지났어요. 마이페이지에서 예매 상태를 확인해 주세요.',
          );
          return;
        }
      }
      setNotice(
        '결제를 처리하는 데 시간이 걸리고 있어요. 마이페이지에서 결제 결과를 확인해 주세요.',
      );
    } catch (error) {
      handleFailure(error);
    } finally {
      setPending(false);
    }
  }
  async function cancel() {
    setPending(true);
    try {
      await call(`/api/bookings/${booking.bookingId}/cancel`, 'POST');
      onReset();
    } catch (error) {
      handleFailure(error);
    } finally {
      setPending(false);
    }
  }
  const percent = total > 0 ? Math.round((remaining / total) * 100) : 0;
  const tone = warning || expired;
  return (
    <Card padding="lg" className="grid gap-5">
      <h2 className="text-heading font-bold text-fg">결제</h2>
      <section
        ref={countdownRef}
        aria-label="남은 선점 시간"
        className={cx(
          'grid gap-2 rounded-control p-4',
          tone ? 'bg-warning-soft' : 'bg-canvas',
        )}
      >
        <div className="flex items-baseline justify-between gap-2">
          <span
            className={cx(
              'text-caption',
              tone ? 'text-warning-strong' : 'text-fg-muted',
            )}
          >
            남은 시간
          </span>
          <span
            className={cx(
              'text-title font-bold tabular-nums',
              tone ? 'text-warning-strong' : 'text-fg',
            )}
          >
            {formatRemaining(remaining)}
          </span>
        </div>
        <div
          role="progressbar"
          aria-label="남은 선점 시간"
          aria-valuemin={0}
          aria-valuemax={total}
          aria-valuenow={remaining}
          aria-valuetext={formatRemaining(remaining)}
          className="h-2 overflow-hidden rounded-pill bg-line"
        >
          <div
            className={cx(
              'h-full rounded-pill transition-[width] duration-1000 ease-linear motion-reduce:transition-none',
              tone ? 'bg-warning' : 'bg-brand',
            )}
            style={{ width: `${percent}%` }}
          />
        </div>
        {warned && !expired && (
          <p role="status" className="text-caption text-warning-strong">
            선점 시간이 1분 남았어요. 서둘러 결제해 주세요.
          </p>
        )}
      </section>
      <section aria-label="선택한 좌석" className="grid gap-2">
        <h3 className="text-body font-semibold text-fg">선택한 좌석</h3>
        <ul className="flex flex-wrap gap-2">
          {booking.seats.map((seat) => (
            <li
              key={seat.seatId}
              className="rounded-pill bg-canvas px-3 py-1 text-caption text-fg"
            >
              {seat.label}
            </li>
          ))}
        </ul>
      </section>
      <CouponPanel
        bookingId={booking.bookingId}
        amount={amount}
        onAmount={setAmount}
        onSessionExpired={() => router.push('/login')}
        disabled={pending || expired}
      />
      <div className="flex items-baseline justify-between gap-2 border-t border-line pt-4">
        <span className="text-body text-fg-muted">결제 금액</span>
        <span className="flex flex-wrap items-baseline justify-end gap-x-2">
          {amount.payableAmount !== undefined &&
            amount.payableAmount !== booking.totalAmount && (
              <span className="text-caption text-fg-muted line-through">
                {booking.totalAmount.toLocaleString('ko-KR')}원
              </span>
            )}
          <strong
            data-testid="payable-amount"
            className="text-title font-bold text-brand"
          >
            {(amount.payableAmount ?? booking.totalAmount).toLocaleString(
              'ko-KR',
            )}
            원
          </strong>
        </span>
      </div>
      {notice && (
        <InlineError role="alert">
          <span>
            {notice}{' '}
            <Link href="/my" className="underline">
              마이페이지
            </Link>
          </span>
        </InlineError>
      )}
      <div className="flex flex-col-reverse gap-2 sm:flex-row">
        <Button
          variant="secondary"
          disabled={pending}
          onClick={() => void cancel()}
        >
          취소
        </Button>
        <Button
          className="sm:flex-1"
          loading={pending}
          disabled={expired || amount.coupon?.state === 'RESERVING'}
          onClick={() => void pay()}
        >
          {pending ? '결제 중이에요…' : '결제하기'}
        </Button>
      </div>
      <Dialog
        open={expired}
        onOpenChange={(open) => {
          if (!open) onReset();
        }}
      >
        <DialogContent
          title="선점 시간이 끝났어요"
          description="좌석 선점이 풀렸어요. 좌석을 다시 선택해 주세요."
          showClose={false}
          dismissOnOutsideClick={false}
          footer={
            <DialogClose asChild>
              <Button>좌석 다시 선택하기</Button>
            </DialogClose>
          }
        />
      </Dialog>
    </Card>
  );
}
