'use client';

import {
  memo,
  useCallback,
  useId,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
} from 'react';
import { Button } from '@/components/ui/button';
import { cx } from '@/lib/cx';
import type { Seat, SeatStatus } from './booking-shared';

export const won = (value: number) => `${value.toLocaleString('ko-KR')}원`;
const rowOrder = new Intl.Collator('ko-KR', { numeric: true });

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
      className="size-3.5 max-h-full max-w-full fill-none stroke-current stroke-2"
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

type SeatRow = { row: string; seats: Seat[] };
type SeatSectionLayout = { section: string; rows: SeatRow[] };
type SeatState = {
  statuses: Record<number, SeatStatus>;
  selected: number[];
  flashing: ReadonlySet<number>;
  /** 스냅샷을 받은 뒤에만 선택할 수 있다. */
  interactive: boolean;
  onToggle: (seat: Seat) => void;
};
type SeatNavigation = {
  firstId: number | undefined;
  registerSeat: (id: number, element: HTMLButtonElement | null) => void;
  focusSeat: (element: HTMLButtonElement) => void;
  moveSeat: (event: KeyboardEvent<HTMLButtonElement>, id: number) => void;
};

function revealSeat(button: HTMLButtonElement) {
  const viewport = button.closest<HTMLElement>('[data-seat-viewport]');
  const label = button.parentElement?.previousElementSibling;
  const header = viewport?.previousElementSibling;
  if (!viewport || !label || !header) return;

  const seat = button.getBoundingClientRect();
  if (seat.width === 0) return;
  const focusSpace = 5;
  const left = label.getBoundingClientRect().right + 4;
  const right = viewport.getBoundingClientRect().right - focusSpace;
  const top = Math.max(65, header.getBoundingClientRect().bottom) + focusSpace;
  const bottom =
    window.innerHeight -
    (window.matchMedia('(min-width: 64rem)').matches ? focusSpace : 96);
  if (seat.left < left) viewport.scrollBy(seat.left - left, 0);
  else if (seat.right > right) viewport.scrollBy(seat.right - right, 0);
  if (seat.bottom > bottom) window.scrollBy(0, seat.bottom - bottom);
  else if (seat.top < top) window.scrollBy(0, seat.top - top);
}

export function SeatGrid({ seats, ...state }: { seats: Seat[] } & SeatState) {
  const { sections, lines, positions } = useMemo(() => {
    const sections: SeatSectionLayout[] = [];
    for (const seat of seats) {
      let section = sections.find((item) => item.section === seat.section);
      if (!section) {
        section = { section: seat.section, rows: [] };
        sections.push(section);
      }
      let row = section.rows.find((item) => item.row === seat.row);
      if (!row) {
        row = { row: seat.row, seats: [] };
        section.rows.push(row);
      }
      row.seats.push(seat);
    }
    for (const section of sections) {
      section.rows.sort((a, b) => rowOrder.compare(a.row, b.row));
    }
    const lines = sections.flatMap((section) =>
      section.rows.map((row) => row.seats),
    );
    const positions = new Map<number, { line: number; column: number }>();
    lines.forEach((row, line) => {
      row.forEach((seat, column) =>
        positions.set(seat.seatId, { line, column }),
      );
    });
    return { sections, lines, positions };
  }, [seats]);
  const refs = useRef(new Map<number, HTMLButtonElement>());
  const tabStop = useRef<HTMLButtonElement | null>(null);
  const firstId = lines[0]?.[0]?.seatId;

  const focusSeat = useCallback((element: HTMLButtonElement) => {
    if (element === tabStop.current) return;
    // 키 반복 중에는 나가는 좌석과 들어오는 좌석의 탭 순서만 갱신한다.
    if (tabStop.current) tabStop.current.tabIndex = -1;
    element.tabIndex = 0;
    tabStop.current = element;
  }, []);
  const registerSeat = useCallback(
    (id: number, element: HTMLButtonElement | null) => {
      if (element) refs.current.set(id, element);
      else refs.current.delete(id);
    },
    [],
  );
  useLayoutEffect(() => {
    const first = firstId === undefined ? undefined : refs.current.get(firstId);
    if (tabStop.current?.isConnected) {
      tabStop.current.tabIndex = 0;
      if (first && first !== tabStop.current) first.tabIndex = -1;
    } else if (first) {
      focusSeat(first);
    }
  }, [positions, firstId, focusSeat]);

  const moveSeat = useCallback(
    (event: KeyboardEvent<HTMLButtonElement>, id: number) => {
      const action = keyMoves[event.key];
      const position = positions.get(id);
      if (!action || !position) return;
      event.preventDefault();
      const { line: lineIndex, column } = position;
      const line = lines[lineIndex];
      let next: Seat | undefined;
      if (action === 'left') next = line[column - 1];
      else if (action === 'right') next = line[column + 1];
      else if (action === 'home') next = line[0];
      else if (action === 'end') next = line[line.length - 1];
      else {
        const target = lines[lineIndex + (action === 'up' ? -1 : 1)];
        next = target?.[Math.min(column, target.length - 1)];
      }
      const button =
        next && next.seatId !== id ? refs.current.get(next.seatId) : undefined;
      if (button) {
        button.focus({ preventScroll: true });
        revealSeat(button);
      }
    },
    [lines, positions],
  );

  return (
    <div className="grid min-w-0 grid-cols-[minmax(0,1fr)] gap-3">
      <div
        aria-hidden="true"
        className="mx-auto w-4/5 rounded-b-card border border-line bg-disabled py-1 text-center text-caption font-semibold text-fg-muted"
      >
        무대
      </div>
      <div
        role="group"
        aria-label="좌석 배치"
        className="grid grid-cols-[minmax(0,1fr)] gap-3"
      >
        {sections.map((layout) => (
          <SeatSection
            key={layout.section}
            layout={layout}
            firstId={firstId}
            registerSeat={registerSeat}
            focusSeat={focusSeat}
            moveSeat={moveSeat}
            {...state}
          />
        ))}
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

function SeatSection({
  layout,
  ...props
}: { layout: SeatSectionLayout } & SeatState & SeatNavigation) {
  const { section, rows } = layout;
  const [zoomed, setZoomed] = useState(true);
  const [tip, setTip] = useState<Seat | null>(null);
  const viewportId = useId();
  const header = useRef<HTMLDivElement>(null);
  const viewport = useRef<HTMLDivElement>(null);
  const visibleRow = useRef<{ element: HTMLElement; top: number } | null>(null);
  const grades = useMemo(
    () => [
      ...new Map(
        rows
          .flatMap((row) => row.seats)
          .map((seat) => [seat.grade, seat.price]),
      ),
    ],
    [rows],
  );

  function toggleZoom() {
    const edge = header.current?.getBoundingClientRect().bottom;
    if (
      edge !== undefined &&
      viewport.current &&
      viewport.current.getBoundingClientRect().top < edge
    ) {
      const row = [
        ...viewport.current.querySelectorAll<HTMLElement>('[data-seat-row]'),
      ].find((element) => element.getBoundingClientRect().bottom > edge);
      if (row)
        visibleRow.current = {
          element: row,
          top: row.getBoundingClientRect().top,
        };
    }
    setZoomed((value) => !value);
  }

  useLayoutEffect(() => {
    const anchor = visibleRow.current;
    if (!anchor) return;
    visibleRow.current = null;
    window.scrollBy(0, anchor.element.getBoundingClientRect().top - anchor.top);
  }, [zoomed]);

  return (
    <div
      role="group"
      aria-label={`${section}구역`}
      className="min-w-0 rounded-control border border-line px-3 pb-3"
    >
      <div
        ref={header}
        className="sticky top-[57px] z-20 -mx-3 rounded-t-control border-b border-line bg-surface px-3 py-2 md:top-[65px]"
      >
        <div className="flex items-center justify-between gap-3">
          <span className="text-body font-semibold text-fg">{section}구역</span>
          <Button
            variant="secondary"
            aria-label={`${section}구역 ${zoomed ? '전체 보기' : '확대'}`}
            aria-controls={viewportId}
            onClick={toggleZoom}
          >
            {zoomed ? '전체 보기' : '확대'}
          </Button>
        </div>
        <p className="text-caption text-fg-muted">
          {grades.map(([grade, price]) => `${grade} ${won(price)}`).join(' · ')}
        </p>
        <p role="status" className="min-h-5 text-caption font-semibold text-fg">
          {tip && seatTip(tip)}
        </p>
      </div>
      <div
        ref={viewport}
        id={viewportId}
        data-seat-viewport
        className="-mx-1.5 mt-1 overflow-x-auto scroll-pl-12 p-1.5"
      >
        <SeatRows rows={rows} zoomed={zoomed} onTip={setTip} {...props} />
      </div>
    </div>
  );
}

const SeatRows = memo(function SeatRows({
  rows,
  zoomed,
  onTip,
  statuses,
  selected,
  flashing,
  interactive,
  onToggle,
  firstId,
  registerSeat,
  focusSeat,
  moveSeat,
}: {
  rows: SeatRow[];
  zoomed: boolean;
  onTip: (seat: Seat) => void;
} & SeatState &
  SeatNavigation) {
  const columnCount = Math.max(...rows.map((row) => row.seats.length));
  return (
    <div
      className={cx(
        'grid grid-cols-[max-content_minmax(0,1fr)] items-center gap-x-1',
        zoomed && 'w-max min-w-full',
      )}
    >
      {rows.map(({ row, seats }) => (
        <div
          key={row}
          data-seat-row
          className="col-span-2 grid grid-cols-subgrid items-center"
        >
          <span
            aria-hidden="true"
            className="sticky left-0 z-10 flex min-w-6 items-center self-stretch bg-surface pr-1 pb-1 text-micro font-semibold whitespace-nowrap text-fg-muted before:absolute before:inset-y-0 before:-left-1.5 before:w-1.5 before:bg-surface"
          >
            {row}열
          </span>
          <div
            className={cx(
              'grid pb-1',
              zoomed ? 'gap-1' : 'gap-px sm:gap-0.5 lg:gap-1',
            )}
            style={{
              gridTemplateColumns: `repeat(${columnCount}, ${zoomed ? '2.75rem' : 'minmax(0.25rem, 2rem)'})`,
            }}
          >
            {seats.map((seat) => {
              const status = statuses[seat.seatId];
              const isSelected = selected.includes(seat.seatId);
              const available = interactive && status === 'AVAILABLE';
              return (
                <button
                  key={seat.seatId}
                  ref={(element) => registerSeat(seat.seatId, element)}
                  type="button"
                  aria-label={seatLabel(seat, status)}
                  aria-pressed={isSelected}
                  aria-disabled={!isSelected && !available}
                  tabIndex={seat.seatId === firstId ? 0 : -1}
                  className={cx(
                    chipBase,
                    'aspect-square min-w-0 scroll-mt-44 scroll-mb-24 lg:scroll-mb-4',
                    chipClass(status, isSelected, flashing.has(seat.seatId)),
                  )}
                  onFocus={(event) => {
                    focusSeat(event.currentTarget);
                    onTip(seat);
                  }}
                  onMouseEnter={() => onTip(seat)}
                  onKeyDown={(event) => moveSeat(event, seat.seatId)}
                  onClick={() => {
                    onTip(seat);
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
  );
});

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
