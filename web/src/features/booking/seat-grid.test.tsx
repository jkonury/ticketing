import {
  act,
  cleanup,
  fireEvent,
  render,
  screen,
} from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { SeatGrid, seatLabel } from './seat-grid';
import type { Seat, SeatStatus } from './booking-shared';

const seats: Seat[] = [
  ...[1, 2, 3].map((number) => ({
    seatId: number,
    section: 'A',
    row: '1',
    number,
    grade: 'VIP',
    price: 150000,
  })),
  ...[1, 2, 3].map((number) => ({
    seatId: 10 + number,
    section: 'A',
    row: '2',
    number,
    grade: 'R',
    price: 100000,
  })),
];
const statuses: Record<number, SeatStatus> = {
  1: 'AVAILABLE',
  2: 'HELD',
  3: 'SOLD',
  11: 'AVAILABLE',
  12: 'AVAILABLE',
  13: 'AVAILABLE',
};

function setup(selected: number[] = [], onToggle = vi.fn()) {
  render(
    <SeatGrid
      seats={seats}
      statuses={statuses}
      selected={selected}
      flashing={new Set([2])}
      interactive
      zoomed={false}
      onToggle={onToggle}
    />,
  );
  return onToggle;
}
const seat = (name: RegExp) => screen.getByRole('button', { name });

describe('좌석 그리드', () => {
  afterEach(cleanup);
  it('좌석 레이블에 위치, 등급, 가격, 상태를 담는다', () => {
    expect(seatLabel(seats[0], 'AVAILABLE')).toBe(
      'A구역 1열 1번, VIP석 150,000원, 선택 가능',
    );
    setup([11]);
    expect(seat(/1열 2번.*선점됨/)).toHaveAttribute('aria-disabled', 'true');
    expect(seat(/1열 3번.*판매됨/)).toHaveAttribute('aria-disabled', 'true');
    expect(seat(/2열 1번.*선택 가능/)).toHaveAttribute('aria-pressed', 'true');
    expect(seat(/1열 1번.*선택 가능/)).toHaveAttribute('aria-pressed', 'false');
  });

  it('구역 머리에 등급과 가격 범례를 보여 준다', () => {
    setup();
    expect(screen.getByText('VIP 150,000원 · R 100,000원')).toBeInTheDocument();
  });

  it('탭 정지는 하나이고 방향키로 행과 열을 이동한다', () => {
    setup();
    const stops = screen
      .getAllByRole('button')
      .filter((button) => button.tabIndex === 0);
    expect(stops).toHaveLength(1);
    const first = seat(/1열 1번/);
    first.focus();
    fireEvent.keyDown(first, { key: 'ArrowRight' });
    expect(seat(/1열 2번/)).toHaveFocus();
    fireEvent.keyDown(seat(/1열 2번/), { key: 'ArrowDown' });
    expect(seat(/2열 2번/)).toHaveFocus();
    expect(seat(/2열 2번/).tabIndex).toBe(0);
    fireEvent.keyDown(seat(/2열 2번/), { key: 'End' });
    expect(seat(/2열 3번/)).toHaveFocus();
    fireEvent.keyDown(seat(/2열 3번/), { key: 'Home' });
    expect(seat(/2열 1번/)).toHaveFocus();
    fireEvent.keyDown(seat(/2열 1번/), { key: 'ArrowUp' });
    expect(seat(/1열 1번/)).toHaveFocus();
  });

  it('포커스하면 툴팁 문구를 보여 주고 선택 가능한 좌석만 토글한다', () => {
    const onToggle = setup();
    act(() => seat(/1열 1번/).focus());
    expect(
      screen.getByText('A구역 1열 1번 · VIP 150,000원'),
    ).toBeInTheDocument();
    fireEvent.click(seat(/1열 2번/));
    fireEvent.click(seat(/1열 3번/));
    expect(onToggle).not.toHaveBeenCalled();
    fireEvent.click(seat(/1열 1번/));
    expect(onToggle).toHaveBeenCalledWith(seats[0]);
  });
});
