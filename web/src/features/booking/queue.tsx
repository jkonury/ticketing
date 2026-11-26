'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { z } from 'zod';
import { Button } from '@/components/ui/button';
import { Card } from '@/components/ui/card';
import { InlineError } from '@/components/ui/feedback';
import { useEventStream } from '@/features/stream/use-event-stream';
import {
  admissionSchema,
  call,
  codeOf,
  queueSchema,
  type Admission,
} from './booking-shared';
import { bookingError } from './error-messages';

export function Queue({
  scheduleId,
  onAdmitted,
}: {
  scheduleId: number;
  onAdmitted: (admission: Admission) => void;
}) {
  const [waiting, setWaiting] = useState(false);
  const [showQueue, setShowQueue] = useState(false);
  const [removed, setRemoved] = useState(false);
  const [position, setPosition] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);
  const router = useRouter();
  useEffect(() => {
    let active = true;
    void call(`/api/queues/${scheduleId}/entries`, 'POST')
      .then((data) => {
        if (!active) return;
        const entry = queueSchema.parse(data);
        if (entry.status === 'ADMITTED')
          onAdmitted(admissionSchema.parse(entry));
        else {
          setPosition(entry.position ?? null);
          setWaiting(true);
        }
      })
      .catch((failure: unknown) => {
        if (active) {
          const code = codeOf(failure);
          if (code === 'SESSION_EXPIRED') router.push('/login');
          setError(bookingError(code));
        }
      });
    return () => {
      active = false;
    };
  }, [scheduleId, attempt, onAdmitted, router]);
  useEffect(() => {
    if (!waiting) return;
    const timer = setTimeout(() => setShowQueue(true), 2000);
    return () => clearTimeout(timer);
  }, [waiting]);
  const stream = useEventStream({
    request: { kind: 'queue', scheduleId },
    enabled: waiting,
    handlers: {
      position(data) {
        const parsed = z.object({ position: z.number() }).safeParse(data);
        if (parsed.success) setPosition(parsed.data.position);
      },
      admitted(data) {
        const parsed = admissionSchema.safeParse(data);
        if (parsed.success) {
          stream.close();
          onAdmitted(parsed.data);
        }
      },
      removed() {
        stream.close();
        setWaiting(false);
        setShowQueue(false);
        setRemoved(true);
      },
    },
  });
  return (
    <Card padding="lg" className="grid gap-4">
      {showQueue && waiting ? (
        <>
          <h2 className="text-heading font-bold text-fg">대기열</h2>
          <p>대기 순번: {position ?? '확인 중'}</p>
        </>
      ) : (
        !removed &&
        !error && (
          <p role="status" className="text-body text-fg-muted">
            입장을 확인하고 있습니다.
          </p>
        )
      )}
      {waiting &&
        stream.state !== 'open' &&
        (stream.state === 'failed' ? (
          <InlineError role="alert">
            대기열 연결에 실패했습니다.{' '}
            <Button variant="secondary" onClick={stream.restart}>
              다시 연결
            </Button>
          </InlineError>
        ) : (
          <p role="status" className="text-caption text-fg-muted">
            {stream.state === 'reconnecting'
              ? '대기열에 다시 연결하고 있습니다. 순번이 오래되었을 수 있습니다.'
              : '대기열에 연결하고 있습니다.'}
          </p>
        ))}
      {removed && (
        <>
          <p>대기열에서 제외되었습니다.</p>
          <div>
            <Button
              onClick={() => {
                setRemoved(false);
                setAttempt((value) => value + 1);
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
