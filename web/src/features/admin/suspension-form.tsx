'use client';

import { useActionState, useState } from 'react';
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
  changeMemberSuspensionAction,
  changePerformanceSuspensionAction,
  type SuspensionState,
} from './suspension-actions';

const copy = {
  member: {
    action: changeMemberSuspensionAction,
    suspend: '이용 정지',
    resume: '이용 정지 해제',
    description: '이 회원의 로그인과 토큰 갱신을 막아요.',
  },
  performance: {
    action: changePerformanceSuspensionAction,
    suspend: '판매 중지',
    resume: '판매 재개',
    description: '공개 목록에서 공연을 숨기고 신규 예매를 막아요.',
  },
};

export function SuspensionForm({
  id,
  target,
  name,
  suspended,
}: {
  id: number;
  target: keyof typeof copy;
  name: string;
  suspended: boolean;
}) {
  const actionCopy = copy[target];
  const intent = suspended ? 'resume' : 'suspend';
  const label = actionCopy[intent];
  const [state, action, pending] = useActionState<SuspensionState, FormData>(
    actionCopy.action,
    { message: '' },
  );
  const [reason, setReason] = useState('');
  const variant = suspended ? 'secondary' : 'danger';

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
        {state.ok ? (
          <>
            <p role="status" className="text-body">
              {state.message}
            </p>
            <DialogClose asChild>
              <Button>닫기</Button>
            </DialogClose>
          </>
        ) : (
          <form action={action} className="grid gap-4">
            <input type="hidden" name="id" value={id} />
            <input type="hidden" name="intent" value={intent} />
            {!suspended && (
              <>
                <p className="text-body text-fg-muted">
                  {actionCopy.description}
                </p>
                <Field label="사유" hint="최대 200자. 감사 로그에 기록돼요.">
                  {(control) => (
                    <Textarea
                      {...control}
                      name="reason"
                      rows={3}
                      maxLength={200}
                      required
                      value={reason}
                      onChange={(event) => setReason(event.currentTarget.value)}
                    />
                  )}
                </Field>
              </>
            )}
            <FormMessage message={state.message} />
            <div className="flex flex-wrap justify-end gap-2">
              <DialogClose asChild>
                <Button variant="secondary" disabled={pending}>
                  취소
                </Button>
              </DialogClose>
              <Button type="submit" variant={variant} loading={pending}>
                {label}
              </Button>
            </div>
          </form>
        )}
      </DialogContent>
    </Dialog>
  );
}
