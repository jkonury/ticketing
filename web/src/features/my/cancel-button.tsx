'use client';
import { useState } from 'react';
import { useRouter } from 'next/navigation';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogTrigger,
} from '@/components/ui/dialog';
import { InlineError } from '@/components/ui/feedback';
export function CancelButton({ id }: { id: number }) {
  const [pending, setPending] = useState(false);
  const [error, setError] = useState(false);
  const [open, setOpen] = useState(false);
  const router = useRouter();
  async function cancel() {
    setPending(true);
    setError(false);
    try {
      const response = await fetch(`/api/bookings/${id}/cancel`, {
        method: 'POST',
      });
      if (!response.ok) throw new Error('cancel');
      setOpen(false);
      router.refresh();
    } catch {
      setOpen(false);
      setError(true);
    } finally {
      setPending(false);
    }
  }
  return (
    <>
      <Dialog open={open} onOpenChange={(next) => !pending && setOpen(next)}>
        <DialogTrigger asChild>
          <Button variant="danger" disabled={pending}>
            {pending ? '취소 중' : '예매 취소'}
          </Button>
        </DialogTrigger>
        <DialogContent
          title="예매를 취소할까요?"
          description="취소하면 선택한 좌석이 풀리고 되돌릴 수 없어요."
          dismissOnOutsideClick={false}
          footer={
            <>
              <DialogClose asChild>
                <Button variant="secondary" disabled={pending}>
                  돌아가기
                </Button>
              </DialogClose>
              <Button
                variant="danger"
                disabled={pending}
                onClick={() => void cancel()}
              >
                {pending ? '취소 중' : '확인'}
              </Button>
            </>
          }
        />
      </Dialog>
      {error && (
        <InlineError role="alert" className="mt-2">
          취소하지 못했습니다. 다시 시도해 주세요.
        </InlineError>
      )}
    </>
  );
}
