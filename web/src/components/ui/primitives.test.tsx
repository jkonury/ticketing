import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { Button } from './button';
import { Dialog, DialogContent, DialogTrigger } from './dialog';
import { Field } from './field';
import { Input } from './input';
import { Sheet, SheetContent, SheetTrigger } from './sheet';
import { Toaster, toast } from './toast';

describe('Button', () => {
  it('처리 중이면 비활성화하고 진행 중임을 알린다', () => {
    render(<Button loading>결제하기</Button>);
    const button = screen.getByRole('button', { name: '결제하기' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
  });

  it('기본 type은 button이라 폼을 실수로 제출하지 않는다', () => {
    render(<Button>취소</Button>);
    expect(screen.getByRole('button', { name: '취소' })).toHaveAttribute(
      'type',
      'button',
    );
  });
});

describe('Field', () => {
  it('레이블, 도움말, 오류를 입력과 연결한다', () => {
    render(
      <Field
        label="비밀번호"
        hint="8자 이상 입력해 주세요."
        error="너무 짧아요."
      >
        {(control) => <Input type="password" {...control} />}
      </Field>,
    );
    const input = screen.getByLabelText('비밀번호');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(
      '8자 이상 입력해 주세요. 너무 짧아요.',
    );
  });

  it('오류가 없으면 invalid로 표시하지 않는다', () => {
    render(<Field label="이메일">{(control) => <Input {...control} />}</Field>);
    expect(screen.getByLabelText('이메일')).not.toHaveAttribute('aria-invalid');
  });
});

describe('Dialog', () => {
  it('열면 제목으로 이름이 붙고, Esc로 닫으면 포커스가 트리거로 돌아간다', async () => {
    const user = userEvent.setup();
    render(
      <Dialog>
        <DialogTrigger>예매 취소</DialogTrigger>
        <DialogContent
          title="예매를 취소할까요?"
          description="취소하면 되돌릴 수 없어요."
        />
      </Dialog>,
    );
    const trigger = screen.getByRole('button', { name: '예매 취소' });
    await user.click(trigger);
    const dialog = screen.getByRole('dialog', { name: '예매를 취소할까요?' });
    expect(dialog).toHaveAccessibleDescription('취소하면 되돌릴 수 없어요.');

    await user.keyboard('{Escape}');
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    );
    expect(trigger).toHaveFocus();
  });
});

describe('Sheet', () => {
  it('제목을 숨겨도 보조기술용 이름을 갖고 닫기 버튼으로 닫힌다', async () => {
    const user = userEvent.setup();
    render(
      <Sheet>
        <SheetTrigger>메뉴</SheetTrigger>
        <SheetContent title="메뉴" hideTitle>
          <a href="/my">마이페이지</a>
        </SheetContent>
      </Sheet>,
    );
    await user.click(screen.getByRole('button', { name: '메뉴' }));
    expect(screen.getByRole('dialog', { name: '메뉴' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: '닫기' }));
    await waitFor(() =>
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
    );
  });
});

describe('Toast', () => {
  it('어디서든 toast()로 띄우고 키보드로 닫는다', async () => {
    const user = userEvent.setup();
    render(
      <>
        <button
          onClick={() => toast({ title: '쿠폰을 받았어요', tone: 'success' })}
        >
          받기
        </button>
        <Toaster />
      </>,
    );
    await user.click(screen.getByRole('button', { name: '받기' }));
    expect(await screen.findByText('쿠폰을 받았어요')).toBeInTheDocument();

    screen.getByRole('button', { name: '알림 닫기' }).focus();
    await user.keyboard('{Enter}');
    await waitFor(() =>
      expect(screen.queryByText('쿠폰을 받았어요')).not.toBeInTheDocument(),
    );
  });
});
