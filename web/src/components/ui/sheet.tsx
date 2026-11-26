'use client';

import type { ReactNode } from 'react';
import * as RadixDialog from '@radix-ui/react-dialog';
import { cx } from '@/lib/cx';
import { CloseIcon, overlayClassName } from './dialog';

export const Sheet = RadixDialog.Root;
export const SheetTrigger = RadixDialog.Trigger;
export const SheetClose = RadixDialog.Close;

export type SheetSide = 'right' | 'bottom';

const sides: Record<SheetSide, string> = {
  right: 'inset-y-0 right-0 h-dvh w-[min(320px,calc(100vw-48px))]',
  bottom: 'inset-x-0 bottom-0 max-h-[85dvh] rounded-t-card',
};

export interface SheetContentProps {
  title: ReactNode;
  /** 제목을 화면에는 숨기고 보조기술에만 알릴 때 true */
  hideTitle?: boolean;
  side?: SheetSide;
  children?: ReactNode;
  className?: string;
}

/** 화면 가장자리에서 나오는 패널. 헤더 메뉴, 모바일 선택 목록 등에 쓴다. */
export function SheetContent({
  title,
  hideTitle = false,
  side = 'right',
  children,
  className,
}: SheetContentProps) {
  return (
    <RadixDialog.Portal>
      <RadixDialog.Overlay className={overlayClassName} />
      <RadixDialog.Content
        aria-describedby={undefined}
        className={cx(
          'fixed z-50 flex flex-col gap-4 overflow-y-auto bg-surface p-5 shadow-overlay focus-visible:outline-none',
          sides[side],
          className,
        )}
      >
        <RadixDialog.Title
          className={cx(
            'min-h-11 pr-12 text-heading leading-11 font-bold text-fg',
            hideTitle && 'sr-only',
          )}
        >
          {title}
        </RadixDialog.Title>
        {children}
        <CloseIcon />
      </RadixDialog.Content>
    </RadixDialog.Portal>
  );
}
