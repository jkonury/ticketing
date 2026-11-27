import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { PerformanceDetail } from './performance-detail';
import type { PerformanceDetailView } from '@/server/aggregate/performance-detail';
const view: PerformanceDetailView = {
  id: 1,
  title: '공연',
  description: '설명',
  genre: null,
  posterUrl: null,
  venue: { id: 1, name: '공연장' },
  prices: [],
  schedules: [{ id: 2, startsAt: '2026-12-01T10:00:00Z' }],
  availability: { status: 'unavailable' },
  mine: null,
};
it('잔여 좌석 조회 실패를 안내한다', () => {
  render(<PerformanceDetail view={view} />);
  expect(screen.getByText('잔여 좌석 확인 불가')).toBeInTheDocument();
});
it('잔여 좌석이 없으면 예매를 비활성화한다', () => {
  render(
    <PerformanceDetail
      view={{
        ...view,
        availability: {
          status: 'ok',
          data: [{ scheduleId: 2, totalSeats: 10, availableSeats: 0 }],
        },
      }}
    />,
  );
  expect(screen.getByRole('button', { name: '예매하기' })).toBeDisabled();
});
