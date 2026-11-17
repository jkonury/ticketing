import { expect, test } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { grantFixedCoupon, registerMember, seed } from './support/seed';
import { attachJwtGuard } from './support/jwt-guard';

const password = 'test-password-123';

test('결제 화면에서 쿠폰을 적용해 할인된 금액으로 결제한다', async ({
  browser,
  request,
}) => {
  const show = await seed(request);
  const email = `coupon-buyer-${randomUUID()}@example.com`;
  const memberId = await registerMember(request, {
    email,
    password,
    name: 'E2E 쿠폰 구매자',
  });
  const couponName = `E2E 3천원 할인 ${randomUUID()}`;
  await grantFixedCoupon(request, {
    name: couponName,
    discountValue: 3000,
    memberIds: [memberId],
  });
  const context = await browser.newContext();
  const guard = await attachJwtGuard(context);
  const page = await context.newPage();
  try {
    await page.goto('/login');
    await page.getByLabel('이메일').fill(email);
    await page.getByLabel('비밀번호').fill(password);
    await page.getByRole('button', { name: '로그인' }).click();
    await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();

    await page.goto(`/performances/${show.performanceId}`);
    await page.getByRole('link', { name: '예매하기' }).click();
    await expect(page.getByRole('heading', { name: '좌석 선택' })).toBeVisible({
      timeout: 45_000,
    });
    await page
      .getByRole('button', { name: 'A구역 1열 1번, A석, 선택 가능' })
      .click();
    await page.getByRole('button', { name: '예매하기' }).click();
    await expect(
      page.getByRole('heading', { name: '결제', exact: true }),
    ).toBeVisible();
    await expect(page.getByText('결제 금액: 10,000원')).toBeVisible();

    await page.getByLabel('쿠폰 선택').selectOption({ label: couponName });
    await page.getByRole('button', { name: '쿠폰 적용' }).click();
    await expect(page.getByText('쿠폰 적용됨: -3,000원')).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByText('결제 금액: 7,000원')).toBeVisible();

    await page.getByRole('button', { name: '결제하기' }).click();
    await expect(page.getByRole('heading', { name: '예매 완료' })).toBeVisible({
      timeout: 45_000,
    });

    await page.getByRole('link', { name: '마이페이지' }).last().click();
    await expect(
      page.getByRole('listitem').filter({ hasText: couponName }),
    ).toContainText('사용 완료', { timeout: 30_000 });
    await guard.assertClean();
  } finally {
    await context.close();
  }
});
