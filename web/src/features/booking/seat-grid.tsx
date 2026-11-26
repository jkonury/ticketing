'use client';

import { useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { cx } from '@/lib/cx';
import type { Seat, SeatStatus } from './booking-shared';

export const won = (value: number) => `${value.toLocaleString('ko-KR')}원`;

export function statusText(status: SeatStatus | undefined) {
  return status === 'AVAILABLE'
    ? '선택 가능'
    : status === 'HELD'
      ? '선점됨'
      : status === 'SOLD'
        ? '판매됨'
        : '확인 중';
}

export function seatLabel(seat: Seat, status: SeatStatus | undefined) {
  return `${seat.section}구역 ${seat.row}열 ${seat.number}번, ${seat.grade}석 ${won(seat.price)}, ${statusText(status)}`;
}

function seatTip(seat: Seat) {
  return `${seat.section}구역 ${seat.row}열 ${seat.number}번 · ${seat.grade} ${won(seat.price)}`;
}

function Mark({ kind }: { kind: 'check' | 'x' }) {
  return (
    <svg
      viewBox="0 0 12 12"
      aria-hidden="true"
      className="size-3.5 fill-none stroke-current stroke-2"
    >
      {kind === 'check' ? (
        <path d="M2.5 6.5 5 9l4.5-5.5" />
      ) : (
        <path d="M3 3l6 6M9 3 3 9" />
      )}
    </svg>
  );
}

const chipBase =
  'relative m-0 flex items-center justify-center rounded-control border p-0 text-micro cursor-pointer';

function chipClass(
  status: SeatStatus | undefined,
  selected: boolean,
  flashing: boolean,
) {
  if (selected) return 'border-brand bg-brand text-on-brand';
  if (status === 'AVAILABLE')
    return 'border-line-strong bg-seat-available text-fg hover:border-brand';
  if (status === 'HELD')
    return cx(
      'cursor-not-allowed border-seat-held-stripe bg-seat-held text-warning-strong',
      'bg-[repeating-linear-gradient(135deg,var(--color-seat-held-stripe)_0_2px,transparent_2px_6px)]',
      flashing && 'ring-2 ring-warning ring-offset-1 motion-safe:animate-pulse',
    );
  if (status === 'SOLD')
    return 'cursor-not-allowed border-seat-sold bg-seat-sold text-on-seat-sold';
  return 'cursor-not-allowed border-line bg-disabled text-on-disabled';
}

type Move = 'left' | 'right' | 'up' | 'down' | 'home' | 'end';
const keyMoves: Record<string, Move> = {
  ArrowLeft: 'left',
  ArrowRight: 'right',
  ArrowUp: 'up',
  ArrowDown: 'down',
  Home: 'home',
  End: 'end',
};

export function SeatGrid({
  seats,
  statuses,
  selected,
  flashing,
  interactive,
  zoomed,
  onToggle,
}: {
  seats: Seat[];
  statuses: Record<number, SeatStatus>;
  selected: number[];
  flashing: ReadonlySet<number>;
  /** 스냅샷을 받은 뒤에만 선택할 수 있다. */
  interactive: boolean;
  zoomed: boolean;
  onToggle: (seat: Seat) => void;
}) {
  const sections = useMemo(() => {
    const result: {
      section: string;
      rows: { row: string; seats: Seat[] }[];
    }[] = [];
    for (const seat of seats) {
      let section = result.find((item) => item.section === seat.section);
      if (!section) {
        section = { section: seat.section, rows: [] };
        result.push(section);
      }
      let row = section.rows.find((item) => item.row === seat.row);
      if (!row) {
        row = { row: seat.row, seats: [] };
        section.rows.push(row);
      }
      row.seats.push(seat);
    }
    return result;
  }, [seats]);
  const lines = useMemo(
    () => sections.flatMap((section) => section.rows.map((row) => row.seats)),
    [sections],
  );
  const [focusId, setFocusId] = useState<number | null>(null);
  const [tipId, setTipId] = useState<number | null>(null);
  const refs = useRef(new Map<number, HTMLButtonElement>());
  const tabId =
    focusId !== null && seats.some((seat) => seat.seatId === focusId)
      ? focusId
      : (seats[0]?.seatId ?? null);
  const tipSeat = seats.find((seat) => seat.seatId === tipId);

  function move(seat: Seat, action: Move) {
    const lineIndex = lines.findIndex((line) => line.includes(seat));
    const line = lines[lineIndex];
    const column = line.indexOf(seat);
    let next: Seat | undefined;
    if (action === 'left') next = line[column - 1];
    else if (action === 'right') next = line[column + 1];
    else if (action === 'home') next = line[0];
    else if (action === 'end') next = line[line.length - 1];
    else {
      const target = lines[lineIndex + (action === 'up' ? -1 : 1)];
      next = target?.[Math.min(column, target.length - 1)];
    }
    if (next) refs.current.get(next.seatId)?.focus();
  }
  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, seat: Seat) {
    const action = keyMoves[event.key];
    if (!action) return;
    event.preventDefault();
    move(seat, action);
  }

  return (
    <div className="grid gap-3">
      <div
        aria-hidden="true"
        className="mx-auto w-4/5 rounded-b-card border border-line bg-disabled py-1 text-center text-caption font-semibold text-fg-muted"
      >
        무대
      </div>
      <p
        role="status"
        className="min-h-6 text-center text-caption font-semibold text-fg"
      >
        {tipSeat ? (
          seatTip(tipSeat)
        ) : (
          <span className="font-normal text-fg-muted">
            좌석에 포커스하거나 탭하면 정보를 보여 줘요.
          </span>
        )}
      </p>
      <div
        role="group"
        aria-label="좌석 배치"
        className={cx('grid gap-3', zoomed && 'overflow-x-auto')}
      >
        {sections.map(({ section, rows }) => {
          const grades = [
            ...new Map(
              rows.flatMap((row) => row.seats).map((s) => [s.grade, s.price]),
            ),
          ];
          return (
            <div
              key={section}
              role="group"
              aria-label={`${section}구역`}
              className={cx(
                'rounded-control border border-line p-3',
                zoomed && 'w-max min-w-full',
              )}
            >
              <div className="mb-2 flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span className="text-body font-semibold text-fg">
                  {section}구역
                </span>
                <span className="text-caption text-fg-muted">
                  {grades
                    .map(([grade, price]) => `${grade} ${won(price)}`)
                    .join(' · ')}
                </span>
              </div>
              <div className="grid gap-1">
                {rows.map(({ row, seats: rowSeats }) => (
                  <div key={row} className="flex items-center gap-1">
                    <span
                      aria-hidden="true"
                      className="w-6 shrink-0 text-micro text-fg-muted"
                    >
                      {row}열
                    </span>
                    <div className="flex min-w-0 flex-1 gap-1">
                      {rowSeats.map((seat) => {
                        const status = statuses[seat.seatId];
                        const isSelected = selected.includes(seat.seatId);
                        const available = interactive && status === 'AVAILABLE';
                        return (
                          <button
                            key={seat.seatId}
                            ref={(element) => {
                              if (element)
                                refs.current.set(seat.seatId, element);
                              else refs.current.delete(seat.seatId);
                            }}
                            type="button"
                            aria-label={seatLabel(seat, status)}
                            aria-pressed={isSelected}
                            aria-disabled={!isSelected && !available}
                            tabIndex={seat.seatId === tabId ? 0 : -1}
                            className={cx(
                              chipBase,
                              zoomed
                                ? 'size-11 shrink-0'
                                : 'aspect-square max-w-8 min-w-0 flex-1',
                              'lg:size-8 lg:flex-none',
                              chipClass(
                                status,
                                isSelected,
                                flashing.has(seat.seatId),
                              ),
                            )}
                            onFocus={() => {
                              setFocusId(seat.seatId);
                              setTipId(seat.seatId);
                            }}
                            onMouseEnter={() => setTipId(seat.seatId)}
                            onKeyDown={(event) => onKeyDown(event, seat)}
                            onClick={() => {
                              setTipId(seat.seatId);
                              if (isSelected || available) onToggle(seat);
                            }}
                          >
                            {isSelected ? (
                              <Mark kind="check" />
                            ) : status === 'SOLD' ? (
                              <Mark kind="x" />
                            ) : null}
                          </button>
                        );
                      })}
                    </div>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
      <ul className="flex flex-wrap justify-center gap-x-4 gap-y-1 text-caption text-fg-muted">
        <Legend name="선택 가능" status="AVAILABLE" />
        <Legend name="내 선택" status="AVAILABLE" selected />
        <Legend name="다른 사용자 선점" status="HELD" />
        <Legend name="판매됨" status="SOLD" />
      </ul>
    </div>
  );
}

function Legend({
  name,
  status,
  selected = false,
}: {
  name: string;
  status: SeatStatus;
  selected?: boolean;
}) {
  return (
    <li className="flex items-center gap-1.5">
      <span
        aria-hidden="true"
        className={cx(
          chipBase,
          'size-5 cursor-default',
          chipClass(status, selected, false),
        )}
      >
        {selected ? (
          <Mark kind="check" />
        ) : status === 'SOLD' ? (
          <Mark kind="x" />
        ) : null}
      </span>
      {name}
    </li>
  );
}
