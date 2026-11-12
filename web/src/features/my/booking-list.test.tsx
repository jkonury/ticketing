import { render, screen } from '@testing-library/react';
import { expect, it } from 'vitest';
import { BookingList } from './booking-list';
it('결제 상태 조회 실패를 안내한다', () => {
  render(
    <BookingList
      view={{ paymentsStatus: 'unavailable', bookings: [], nextCursor: null }}
    />,
  );
  expect(
    screen.getByText('결제 상태를 불러오지 못했습니다'),
  ).toBeInTheDocument();
});
