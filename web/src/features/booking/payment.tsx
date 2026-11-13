'use client';

import { useEffect, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { z } from 'zod';
import { call, codeOf, type Booking } from './booking-shared';
import { bookingError } from './error-messages';

export function Payment({
  booking,
  onDone,
  onReset,
}: {
  booking: Booking;
  onDone: () => void;
  onReset: () => void;
}) {
  const [remaining, setRemaining] = useState(() =>
    Math.max(
      0,
      Math.ceil(
        (Date.parse(booking.expiresAt) -
          Date.parse(booking.serverTime) -
          (performance.now() - booking.receivedAt)) /
          1000,
      ),
    ),
  );
  const [pending, setPending] = useState(false);
  const [notice, setNotice] = useState('');
  const paymentKey = useRef(crypto.randomUUID());
  const router = useRouter();
  useEffect(() => {
    const timer = setInterval(
      () =>
        setRemaining(
          Math.max(
            0,
            Math.ceil(
              (Date.parse(booking.expiresAt) -
                Date.parse(booking.serverTime) -
                (performance.now() - booking.receivedAt)) /
                1000,
            ),
          ),
        ),
      1000,
    );
    return () => clearInterval(timer);
  }, [booking.expiresAt, booking.serverTime, booking.receivedAt]);
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
          setNotice('결제가 실패했거나 예매가 만료되었습니다.');
          return;
        }
      }
      setNotice('처리 중입니다. 마이페이지에서 확인하세요.');
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
  return (
    <section>
      <h2>결제</h2>
      <p>선택한 좌석: {booking.seats.map((seat) => seat.label).join(', ')}</p>
      <p>남은 시간: {remaining}초</p>
      <p>{booking.totalAmount.toLocaleString('ko-KR')}원</p>
      {remaining === 0 && <p>선점이 만료되었습니다.</p>}
      {notice && (
        <p role="alert">
          {notice} <Link href="/my">마이페이지</Link>
        </p>
      )}
      <button disabled={pending || remaining === 0} onClick={() => void pay()}>
        결제하기
      </button>
      <button disabled={pending} onClick={() => void cancel()}>
        취소
      </button>
    </section>
  );
}
