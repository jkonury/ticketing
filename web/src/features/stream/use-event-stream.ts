'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useRouter } from 'next/navigation';
import { z } from 'zod';

type State = 'connecting' | 'open' | 'reconnecting' | 'closed' | 'failed';
type Options = {
  request: { kind: 'queue' | 'seats'; scheduleId: number };
  handlers: Record<string, (data: unknown, id: string | null) => void>;
  enabled?: boolean;
  freshSnapshot?: boolean;
  onDisconnect?: () => void;
};

export function useEventStream({
  request,
  handlers,
  enabled = true,
  freshSnapshot = false,
  onDisconnect,
}: Options) {
  const { kind, scheduleId } = request;
  const [state, setState] = useState<State>('connecting');
  const router = useRouter();
  const [generation, setGeneration] = useState(0);
  const restart = useCallback(() => {
    setState('connecting');
    setGeneration((value) => value + 1);
  }, []);
  const handlersRef = useRef(handlers);
  const disconnectRef = useRef(onDisconnect);
  const closeRef = useRef<() => void>(() => {});
  useEffect(() => {
    handlersRef.current = handlers;
    disconnectRef.current = onDisconnect;
  }, [handlers, onDisconnect]);
  const close = useCallback(() => closeRef.current(), []);

  useEffect(() => {
    if (!enabled) return;
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
    closeRef.current = () => {
      stop();
      setState('closed');
    };
    const retry = () => {
      if (stopped) return;
      disconnectRef.current?.();
      setState('reconnecting');
      const delay = Math.min(30000, 1000 * 2 ** failures++);
      timer = setTimeout(
        connect,
        Math.min(30000, delay * (0.8 + Math.random() * 0.4)),
      );
    };
    async function connect() {
      try {
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
          if (
            response.status === 401 &&
            problem.success &&
            problem.data.code === 'SESSION_EXPIRED'
          ) {
            disconnectRef.current?.();
            stop();
            setState('failed');
            router.push('/login');
            return;
          }
          if (
            response.status >= 400 &&
            response.status < 500 &&
            response.status !== 408 &&
            response.status !== 429
          ) {
            disconnectRef.current?.();
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
        source = new EventSource(url);
        source.onopen = () => {
          failures = 0;
          setState('open');
        };
        source.onerror = () => {
          source?.close();
          retry();
        };
        for (const name of Object.keys(handlersRef.current)) {
          source.addEventListener(name, (event: MessageEvent<string>) => {
            if (event.lastEventId) lastEventId = event.lastEventId;
            try {
              handlersRef.current[name]?.(
                event.data ? JSON.parse(event.data) : null,
                event.lastEventId || null,
              );
            } catch {
              /* malformed events are ignored */
            }
          });
        }
      } catch {
        if (!stopped) retry();
      }
    }
    void connect();
    return () => {
      stop();
      closeRef.current = () => {};
    };
  }, [enabled, kind, scheduleId, router, generation, freshSnapshot]);
  return { state, close, restart };
}
