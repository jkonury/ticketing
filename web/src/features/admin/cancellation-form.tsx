'use client';

import { useActionState, useCallback, useState } from 'react';
import { Button } from '@/components/ui/button';
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogTrigger,
} from '@/components/ui/dialog';
import { Field } from '@/components/ui/field';
import { Textarea } from '@/components/ui/input';
import { FormMessage } from '@/components/form-message';
import {
  cancelPerformanceAction,
  forceCancelBookingAction,
  type CancellationState,
} from './cancellation-actions';

const copy = {
  booking: {
    label: '강제 취소',
    action: forceCancelBookingAction,
    warning: '예매를 취소해요. 확정된 예매는 결제 금액을 환불해요.',
  },
  performance: {
    label: '공연 취소',
    action: cancelPerformanceAction,
    warning:
      '공연을 취소하면 되돌릴 수 없어요. 결제 대기 및 확정 예매를 모두 취소하고, 결제한 금액은 순차적으로 환불해요.',
  },
};

type CancellationProps = {
  id: number;
  target: keyof typeof copy;
  name: string;
};

function focusStep(element: HTMLElement | null) {
  element?.focus();
}

function CancellationSteps({ id, target }: Omit<CancellationProps, 'name'>) {
  const { label, action, warning } = copy[target];
  const [state, submit, pending] = useActionState<CancellationState, FormData>(
    action,
    { message: '' },
  );
  const [reason, setReason] = useState('');
  const [confirming, setConfirming] = useState(false);
  const focusReason = useCallback((form: HTMLFormElement | null) => {
    form?.querySelector('textarea')?.focus();
  }, []);

  if (state.ok) {
    return (
      <>
        <p role="status" className="text-body">
          {state.message}
        </p>
        <DialogClose asChild>
          <Button>닫기</Button>
        </DialogClose>
      </>
    );
  }

  return (
    <>
      <p className="text-body text-fg-muted">{warning}</p>
      {confirming ? (
        <form action={submit} className="grid gap-4">
          <input type="hidden" name="id" value={id} />
          <input type="hidden" name="reason" value={reason.trim()} />
          <div className="grid gap-1 rounded-control bg-canvas p-3">
            <p
              ref={focusStep}
              tabIndex={-1}
              className="text-caption font-semibold"
            >
              입력한 사유
            </p>
            <p className="text-body whitespace-pre-wrap wrap-break-word">
              {reason.trim()}
            </p>
          </div>
          <FormMessage message={state.message} />
          <div className="flex flex-wrap justify-end gap-2">
            <Button
              variant="secondary"
              disabled={pending}
              onClick={() => setConfirming(false)}
            >
              사유 수정
            </Button>
            <Button type="submit" variant="danger" loading={pending}>
              {label} 확정
            </Button>
          </div>
        </form>
      ) : (
        <form
          className="grid gap-4"
          ref={focusReason}
          onSubmit={(event) => {
            event.preventDefault();
            if (reason.trim()) setConfirming(true);
          }}
        >
          <Field
            label="취소 사유"
            hint="최대 200자. 감사 로그에 기록돼요."
            required
          >
            {(control) => (
              <Textarea
                {...control}
                rows={3}
                maxLength={200}
                value={reason}
                onChange={(event) => setReason(event.currentTarget.value)}
              />
            )}
          </Field>
          <div className="flex flex-wrap justify-end gap-2">
            <DialogClose asChild>
              <Button variant="secondary">닫기</Button>
            </DialogClose>
            <Button type="submit" disabled={!reason.trim()}>
              다음
            </Button>
          </div>
        </form>
      )}
    </>
  );
}

export function CancellationForm({ id, target, name }: CancellationProps) {
  const { label } = copy[target];

  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button
          variant="secondary"
          className="whitespace-nowrap"
          aria-label={`${name} ${label}`}
        >
          {label}
        </Button>
      </DialogTrigger>
      <DialogContent
        title={label}
        description={name}
        dismissOnOutsideClick={false}
      >
        <CancellationSteps id={id} target={target} />
      </DialogContent>
    </Dialog>
  );
}
