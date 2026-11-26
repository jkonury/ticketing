import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { expect, it, vi } from 'vitest';
import { CancelButton } from './cancel-button';

const refresh = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ refresh }) }));

import { BookingList } from './booking-list';
it('결제 상태 조회 실패를 안내한다', () => {
  render(
    <BookingList
      view={{
        paymentsStatus: 'unavailable',
        couponsStatus: 'ok',
        coupons: [],
        bookings: [],
        nextCursor: null,
      }}
    />,
  );
  expect(
    screen.getByText('결제 상태를 불러오지 못했습니다'),
  ).toBeInTheDocument();
});

it('예매 취소는 확인 다이얼로그를 거친다', async () => {
  const fetchMock = vi.fn().mockResolvedValue({ ok: true });
  vi.stubGlobal('fetch', fetchMock);
  render(<CancelButton id={7} />);
  fireEvent.click(screen.getByRole('button', { name: '예매 취소' }));
  expect(
    await screen.findByRole('dialog', { name: '예매를 취소할까요?' }),
  ).toBeInTheDocument();
  expect(fetchMock).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '돌아가기' }));
  await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
  expect(fetchMock).not.toHaveBeenCalled();
  fireEvent.click(screen.getByRole('button', { name: '예매 취소' }));
  fireEvent.click(await screen.findByRole('button', { name: '확인' }));
  await waitFor(() =>
    expect(fetchMock).toHaveBeenCalledWith('/api/bookings/7/cancel', {
      method: 'POST',
    }),
  );
  await waitFor(() => expect(refresh).toHaveBeenCalled());
});
