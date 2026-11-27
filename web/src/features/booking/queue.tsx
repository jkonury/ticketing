'use client';

import { useCallback, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Card } from '@/components/ui/card';
import { InlineError, LoadingState } from '@/components/ui/feedback';
import { formatSeoul } from '@/lib/datetime';
import { useEventStream } from '@/features/stream/use-event-stream';
import {
  admissionSchema,
  call,
  codeOf,
  queueSchema,
  type Admission,
} from './booking-shared';
import { bookingError } from './error-messages';

/** 내 앞에 남은 인원. 순번은 1부터 시작한다. */
export function aheadOf(position: number) {
  return Math.max(position - 1, 0);
}

/** 처음 받은 순번 대비 진행률(0~100). */
export function progressOf(initial: number, position: number) {
  if (initial <= 1) return position <= 1 ? 100 : 0;
  const done = (initial - Math.max(position, 1)) / (initial - 1);
  return Math.round(Math.min(Math.max(done, 0), 1) * 100);
}

const ANNOUNCE_INTERVAL_MS = 10_000;

export function Queue({
  scheduleId,
  title,
  startsAt,
  onAdmitted,
}: {
  scheduleId: number;
  title?: string;
  startsAt?: string;
  onAdmitted: (admission: Admission) => void;
}) {
  const [waiting, setWaiting] = useState(false);
  const [showQueue, setShowQueue] = useState(false);
  const [removed, setRemoved] = useState(false);
  const [position, setPosition] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [initial, setInitial] = useState<number | null>(null);
  const [announced, setAnnounced] = useState<number | null>(null);
  const latest = useRef<number | null>(null);
  const registration = useRef<AbortController | null>(null);
  const stopWaitingTimers = useRef<(() => void) | null>(null);
  const router = useRouter();
  if (initial === null && position !== null) setInitial(position);
  const updatePosition = useCallback((next: number | null) => {
    latest.current = next;
    setPosition(next);
  }, []);
  const register = useCallback(
    async (signal: AbortSignal) => {
      if (signal.aborted) return;
      try {
        const data = await call(`/api/queues/${scheduleId}/entries`, 'POST');
        if (signal.aborted) return;
        const entry = queueSchema.parse(data);
        if (entry.status === 'ADMITTED') {
          onAdmitted(admissionSchema.parse(entry));
          return;
        }
        updatePosition(entry.position ?? null);
        setWaiting(true);
        setAnnounced(latest.current);
        stopWaitingTimers.current?.();
        const announce = setInterval(
          () => setAnnounced(latest.current),
          ANNOUNCE_INTERVAL_MS,
        );
        const show = setTimeout(() => setShowQueue(true), 2000);
        stopWaitingTimers.current = () => {
          clearInterval(announce);
          clearTimeout(show);
        };
      } catch (failure) {
        if (signal.aborted) return;
        const code = codeOf(failure);
        if (code === 'SESSION_EXPIRED') router.push('/login');
        setError(bookingError(code));
      }
    },
    [scheduleId, onAdmitted, router, updatePosition],
  );
  const queueRef = useCallback(
    (element: HTMLDivElement | null) => {
      if (!element) return;
      const controller = new AbortController();
      registration.current = controller;
      queueMicrotask(() => void register(controller.signal));
      return () => {
        controller.abort();
        stopWaitingTimers.current?.();
      };
    },
    [register],
  );
  function showRemoved() {
    close();
    stopWaitingTimers.current?.();
    setWaiting(false);
    setShowQueue(false);
    setRemoved(true);
  }
  const { state, close, restart, attach } = useEventStream({
    request: { kind: 'queue', scheduleId },
    enabled: waiting,
    async beforeReconnect(signal) {
      try {
        const data = await call(`/api/queues/${scheduleId}/entries/me`, 'GET');
        if (signal.aborted) return;
        const entry = queueSchema.parse(data);
        if (entry.status === 'ADMITTED') {
          const admission = admissionSchema.parse(entry);
          close();
          onAdmitted(admission);
        } else {
          updatePosition(entry.position ?? null);
        }
      } catch (failure) {
        if (!signal.aborted && codeOf(failure) === 'QUEUE_ENTRY_NOT_FOUND')
          showRemoved();
      }
    },
    handlers: {
      position(data) {
        const parsed = z.object({ position: z.number() }).safeParse(data);
        if (parsed.success) updatePosition(parsed.data.position);
      },
      admitted(data) {
        const parsed = admissionSchema.safeParse(data);
        if (parsed.success) {
          close();
          onAdmitted(parsed.data);
        }
      },
      removed: showRemoved,
    },
  });
  const connected = state === 'open';
  const percent =
    position !== null && initial !== null ? progressOf(initial, position) : 0;
  return (
    <Card ref={queueRef} padding="lg" className="grid gap-4">
      <p ref={(element) => attach(element)} role="status" className="sr-only">
        {showQueue && waiting && announced !== null
          ? `현재 대기 순번은 ${announced}번이에요. 내 앞에 ${aheadOf(announced)}명이 있어요.`
          : ''}
      </p>
      {showQueue && waiting ? (
        <>
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-heading font-bold text-fg">대기열</h2>
            <Badge tone={connected ? 'success' : 'warning'}>
              {connected ? '연결됨' : '다시 연결 중'}
            </Badge>
          </div>
          {(title || startsAt) && (
            <p className="text-caption text-fg-muted">
              {title}
              {title && startsAt ? ' · ' : ''}
              {startsAt ? formatSeoul(startsAt) : ''}
            </p>
          )}
          {position === null ? (
            <LoadingState label="순번을 확인하고 있어요." className="py-4" />
          ) : (
            <div className="grid gap-1 text-center">
              <span className="text-caption text-fg-muted">내 대기 순번</span>
              <span
                key={position}
                data-testid="queue-position"
                className="text-display font-bold text-brand tabular-nums motion-safe:animate-queue-swap"
              >
                {position}
              </span>
              <span className="text-body text-fg">
                내 앞에 {aheadOf(position)}명이 있어요
              </span>
            </div>
          )}
          <div
            role="progressbar"
            aria-label="대기 진행률"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={percent}
            className="h-2 overflow-hidden rounded-pill bg-line"
          >
            <div
              className="h-full rounded-pill bg-brand transition-[width] duration-500 motion-reduce:transition-none"
              style={{ width: `${percent}%` }}
            />
          </div>
          <p className="text-caption text-fg-muted">
            새로고침하면 순번이 뒤로 밀릴 수 있어요.
          </p>
        </>
      ) : (
        !removed && !error && <LoadingState label="입장을 확인하고 있어요." />
      )}
      {waiting &&
        state !== 'open' &&
        (state === 'failed' ? (
          <InlineError role="alert">
            대기열 연결에 실패했어요.{' '}
            <Button variant="secondary" onClick={restart}>
              다시 연결
            </Button>
          </InlineError>
        ) : (
          <p role="status" className="text-caption text-fg-muted">
            {state === 'reconnecting'
              ? '대기열에 다시 연결하고 있어요. 순번이 오래되었을 수 있어요.'
              : '대기열에 연결하고 있어요.'}
          </p>
        ))}
      {removed && (
        <>
          <p>대기열에서 제외되었어요.</p>
          <div>
            <Button
              onClick={() => {
                setRemoved(false);
                if (registration.current)
                  void register(registration.current.signal);
              }}
            >
              다시 줄 서기
            </Button>
          </div>
        </>
      )}
      {error && <InlineError role="alert">{error}</InlineError>}
    </Card>
  );
}
