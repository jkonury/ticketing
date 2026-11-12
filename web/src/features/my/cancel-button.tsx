'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
export function CancelButton({ id }: { id: number }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);
  const router = useRouter();
  async function cancel() {
    setPending(true);
    setError(false);
    try {
      const response = await fetch(`/api/bookings/${id}/cancel`, {
        method: 'POST',
      });
      if (!response.ok) throw new Error('cancel');
      router.refresh();
    } catch {
      setError(true);
    } finally {
      setPending(false);
    }
  }
  return (
    <>
      <button type="button" disabled={pending} onClick={cancel}>
        {pending ? '취소 중' : '예매 취소'}
      </button>
      {error && <p role="alert">취소하지 못했습니다. 다시 시도해 주세요.</p>}
    </>
  );
}
