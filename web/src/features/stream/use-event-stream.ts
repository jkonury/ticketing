'use client';

import { useMemo, useSyncExternalStore } from 'react';
import { useRouter } from 'next/navigation';
import { z } from 'zod';

type State = 'connecting' | 'open' | 'reconnecting' | 'closed' | 'failed';
type Options = {
  request: { kind: 'queue' | 'seats'; scheduleId: number };
  handlers: Record<string, (data: unknown, id: string | null) => void>;
  enabled?: boolean;
  freshSnapshot?: boolean;
  onDisconnect?: () => void;
  beforeReconnect?: (signal: AbortSignal) => Promise<void>;
};

function createEventStream(
  kind: Options['request']['kind'],
  scheduleId: number,
  enabled: boolean,
  freshSnapshot: boolean,
  onSessionExpired: () => void,
) {
  let options: Options | undefined;
  let state: State = 'connecting';
  let notify: (() => void) | undefined;
  let stopConnection: (() => void) | undefined;

  function setState(next: State) {
    state = next;
    notify?.();
  }
  function start() {
    stopConnection?.();
    if (!enabled) return;
    setState('connecting');
    let stopped = false;
    let source: EventSource | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let lastEventId: string | undefined;
    let failures = 0;
    const abort = new AbortController();
    const stop = () => {
      stopped = true;
      abort.abort();
      source?.close();
      if (timer) clearTimeout(timer);
    };
    stopConnection = stop;
    const retry = () => {
      if (stopped) return;
      options?.onDisconnect?.();
      setState('reconnecting');
      const delay = Math.min(30000, 1000 * 2 ** failures++);
      timer = setTimeout(
        connect,
        Math.min(30000, delay * (0.8 + Math.random() * 0.4)),
      );
    };
    async function connect() {
      try {
        if (failures > 0) await options?.beforeReconnect?.(abort.signal);
        if (stopped) return;
        const response = await fetch('/api/streams/tickets', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            kind,
            scheduleId,
            ...(kind === 'seats' && lastEventId && !freshSnapshot
              ? { lastEventId }
              : {}),
          }),
          signal: abort.signal,
        });
        if (stopped) return;
        if (!response.ok) {
          const problem = z
            .object({ code: z.string() })
            .safeParse(await response.json().catch(() => null));
          if (stopped) return;
          if (
            response.status === 401 &&
            problem.success &&
            problem.data.code === 'SESSION_EXPIRED'
          ) {
            options?.onDisconnect?.();
            stop();
            setState('failed');
            onSessionExpired();
            return;
          }
          if (
            response.status >= 400 &&
            response.status < 500 &&
            response.status !== 408 &&
            response.status !== 429
          ) {
            options?.onDisconnect?.();
            stop();
            setState('failed');
            return;
          }
          throw new Error('ticket');
        }
        const { url } = z
          .object({ url: z.string() })
          .parse(await response.json());
        if (stopped) return;
        const currentSource = new EventSource(url);
        source = currentSource;
        currentSource.onopen = () => {
          if (stopped || source !== currentSource) return;
          failures = 0;
          setState('open');
        };
        currentSource.onerror = () => {
          if (stopped || source !== currentSource) return;
          currentSource.close();
          source = undefined;
          retry();
        };
        for (const name of Object.keys(options?.handlers ?? {})) {
          currentSource.addEventListener(
            name,
            (event: MessageEvent<string>) => {
              if (stopped || source !== currentSource) return;
              if (event.lastEventId) lastEventId = event.lastEventId;
              try {
                options?.handlers[name]?.(
                  event.data ? JSON.parse(event.data) : null,
                  event.lastEventId || null,
                );
              } catch {
                /* malformed events are ignored */
              }
            },
          );
        }
      } catch {
        if (!stopped) retry();
      }
    }
    queueMicrotask(() => void connect());
  }
  return {
    subscribe(listener: () => void) {
      notify = listener;
      start();
      return () => {
        notify = undefined;
        stopConnection?.();
        stopConnection = undefined;
      };
    },
    getSnapshot: () => state,
    update(next: Options) {
      options = next;
    },
    close() {
      stopConnection?.();
      setState('closed');
    },
    restart: start,
  };
}

const initialSnapshot = (): State => 'connecting';

export function useEventStream(options: Options) {
  const { kind, scheduleId } = options.request;
  const { enabled = true, freshSnapshot = false } = options;
  const router = useRouter();
  const stream = useMemo(
    () =>
      createEventStream(kind, scheduleId, enabled, freshSnapshot, () =>
        router.push('/login'),
      ),
    [kind, scheduleId, enabled, freshSnapshot, router],
  );
  const state = useSyncExternalStore(
    stream.subscribe,
    stream.getSnapshot,
    initialSnapshot,
  );
  return {
    state,
    close: stream.close,
    restart: stream.restart,
    attach(element: HTMLElement | null) {
      if (element) stream.update(options);
    },
  };
}
