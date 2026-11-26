import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { HeaderNav } from './header-nav';

vi.mock('next/navigation', () => ({ usePathname: () => '/my' }));

afterEach(cleanup);

const noop = () => {};

describe('HeaderNav', () => {
  it('USER는 쿠폰·마이페이지 링크를 보고 역할 코드는 보지 않는다', () => {
    render(
      <HeaderNav user={{ name: '홍길동', role: 'USER' }} logoutAction={noop} />,
    );
    expect(screen.getByRole('link', { name: '쿠폰' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '마이페이지' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.queryByText(/USER/)).not.toBeInTheDocument();
    expect(
      screen.getByRole('button', { name: '로그아웃' }),
    ).toBeInTheDocument();
  });

  it('ORGANIZER는 주최자 배지와 주최자 링크를 본다', () => {
    render(
      <HeaderNav
        user={{ name: '기획자', role: 'ORGANIZER' }}
        logoutAction={noop}
      />,
    );
    expect(screen.getByText('주최자')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: '공연 등록' })).toHaveAttribute(
      'href',
      '/organizer/new',
    );
    expect(screen.queryByText(/ORGANIZER/)).not.toBeInTheDocument();
  });

  it('비로그인은 로그인 링크만 보인다', () => {
    render(<HeaderNav user={null} logoutAction={noop} />);
    expect(screen.getByRole('link', { name: '로그인' })).toHaveAttribute(
      'href',
      '/login',
    );
    expect(screen.getAllByRole('link')).toHaveLength(1);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('모바일 메뉴 버튼으로 시트를 열고 링크를 누르면 닫힌다', async () => {
    const user = userEvent.setup();
    render(
      <HeaderNav user={{ name: '홍길동', role: 'USER' }} logoutAction={noop} />,
    );
    await user.click(screen.getByRole('button', { name: '메뉴 열기' }));
    const dialog = await screen.findByRole('dialog');
    const link = Array.from(dialog.querySelectorAll('a')).find(
      (a) => a.textContent === '쿠폰',
    );
    expect(link).toBeDefined();
    await user.click(link!);
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    );
  });
});
