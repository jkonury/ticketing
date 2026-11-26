'use client';

import type { ReactNode } from 'react';
import * as RadixDialog from '@radix-ui/react-dialog';
import { X } from 'lucide-react';
import { cx } from '@/lib/cx';

export const Dialog = RadixDialog.Root;
export const DialogTrigger = RadixDialog.Trigger;
export const DialogClose = RadixDialog.Close;

export const overlayClassName = 'fixed inset-0 z-40 bg-fg/40';

export interface DialogContentProps {
  title: ReactNode;
  description?: ReactNode;
  children?: ReactNode;
  /** 하단 버튼 영역. 주요 행동을 오른쪽 끝에 둔다. */
  footer?: ReactNode;
  /** 확인이 필요한 다이얼로그는 바깥 클릭으로 닫히지 않게 한다. */
  dismissOnOutsideClick?: boolean;
  /** 닫기 아이콘 버튼 표시 여부. 반드시 응답해야 하는 경고에서는 끈다. */
  showClose?: boolean;
  className?: string;
}

export function DialogContent({
  title,
  description,
  children,
  footer,
  dismissOnOutsideClick = true,
  showClose = true,
  className,
}: DialogContentProps) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className={overlayClassName} />
      <RadixDialog.Content
        // description이 없을 때 Radix 경고를 끄려면 undefined를 명시해야 한다.
        {...(description ? {} : { 'aria-describedby': undefined })}
        onPointerDownOutside={
          dismissOnOutsideClick ? undefined : (event) => event.preventDefault()
        }
        className={cx(
          'fixed top-1/2 left-1/2 z-50 grid max-h-[calc(100dvh-32px)] w-[calc(100vw-32px)] max-w-110',
          '-translate-x-1/2 -translate-y-1/2 gap-4 overflow-y-auto rounded-card bg-surface p-6 shadow-overlay',
          'focus-visible:outline-none',
          className,
        )}
      >
        <div className="grid gap-1.5 pr-8">
          <RadixDialog.Title className="text-heading font-bold text-fg">
            {title}
          </RadixDialog.Title>
          {description ? (
            <RadixDialog.Description className="text-body text-fg-muted">
              {description}
            </RadixDialog.Description>
          ) : null}
        </div>
        {children}
        {footer ? (
          <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
            {footer}
          </div>
        ) : null}
        {showClose ? <CloseIcon /> : null}
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}

export function CloseIcon() {
  return (
    <RadixDialog.Close
      aria-label="닫기"
      className="absolute top-3 right-3 inline-flex size-11 cursor-pointer border-0 bg-transparent p-0 items-center justify-center rounded-control text-fg-muted hover:bg-canvas focus-visible:outline-3 focus-visible:outline-brand"
    >
      <X aria-hidden="true" className="size-5" />
    </RadixDialog.Close>
  );
}
