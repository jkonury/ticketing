'use client';

import { useSyncExternalStore, type ReactNode } from 'react';
import * as RadixToast from '@radix-ui/react-toast';
import { CircleAlert, CircleCheck, Info, X } from 'lucide-react';

export type ToastTone = 'info' | 'success' | 'danger';

export interface ToastInput {
  title: string;
  description?: string;
  tone?: ToastTone;
}

interface ToastItem extends ToastInput {
  id: number;
}

// 컨텍스트 대신 모듈 스토어를 쓴다. Next의 instant 검증은 페이지 세그먼트를
// 레이아웃 Provider 없이 렌더링하므로, 컨텍스트 훅은 그 자리에서 실패한다.
let items: ToastItem[] = [];
let nextId = 0;
const listeners = new Set<() => void>();
const EMPTY: ToastItem[] = [];

function emit() {
  for (const listener of listeners) listener();
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/** 일시적인 결과 알림을 띄운다. 화면에는 레이아웃의 Toaster가 그린다. */
export function toast(input: ToastInput): void {
  nextId += 1;
  items = [...items, { ...input, id: nextId }];
  emit();
}

function dismiss(id: number) {
  items = items.filter((item) => item.id !== id);
  emit();
}

const icons = {
  info: <Info aria-hidden="true" className="size-5 shrink-0 text-brand" />,
  success: (
    <CircleCheck aria-hidden="true" className="size-5 shrink-0 text-success" />
  ),
  danger: (
    <CircleAlert aria-hidden="true" className="size-5 shrink-0 text-danger" />
  ),
} satisfies Record<ToastTone, ReactNode>;

/** 레이아웃에 한 번 둔다. */
export function Toaster() {
  const current = useSyncExternalStore(
    subscribe,
    () => items,
    () => EMPTY,
  );

  return (
    <RadixToast.Provider label="알림" swipeDirection="down">
      {current.map((item) => (
        <RadixToast.Root
          key={item.id}
          // 실패 알림은 바로 읽히도록 foreground로 알린다.
          type={item.tone === 'danger' ? 'foreground' : 'background'}
          onOpenChange={(open) => (open ? undefined : dismiss(item.id))}
          className="flex items-start gap-3 rounded-card border border-line bg-surface p-4 shadow-overlay"
        >
          {icons[item.tone ?? 'info']}
          <div className="grid flex-1 gap-0.5">
            <RadixToast.Title className="text-body font-semibold text-fg">
              {item.title}
            </RadixToast.Title>
            {item.description ? (
              <RadixToast.Description className="text-caption text-fg-muted">
                {item.description}
              </RadixToast.Description>
            ) : null}
          </div>
          <RadixToast.Close
            aria-label="알림 닫기"
            className="-m-2 inline-flex size-9 shrink-0 cursor-pointer border-0 bg-transparent p-0 items-center justify-center rounded-control text-fg-muted hover:bg-canvas"
          >
            <X aria-hidden="true" className="size-4" />
          </RadixToast.Close>
        </RadixToast.Root>
      ))}
      <RadixToast.Viewport className="fixed inset-x-0 bottom-0 z-50 mx-auto flex w-full max-w-110 flex-col gap-2 p-4 outline-none" />
    </RadixToast.Provider>
  );
}
