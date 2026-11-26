import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { seed } from './support/seed';
import { attachJwtGuard } from './support/jwt-guard';

const password = 'test-password-123';

async function login(page: Page, email: string, pass: string) {
  await page.goto('/login');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(pass);
  await page.getByRole('button', { name: '로그인' }).click();
  await expect(page.getByRole('button', { name: '로그아웃' })).toBeVisible();
}

async function signupAndLogin(page: Page) {
  const email = `viewer-${randomUUID()}@example.com`;
  await page.goto('/signup');
  await page.getByLabel('이름').fill('E2E 관객');
  await page.getByLabel('이메일').fill(email);
  await page.getByLabel('비밀번호').fill(password);
  await page.getByRole('button', { name: '가입', exact: true }).click();
  await expect(page).toHaveURL(/\/login/);
  await login(page, email, password);
}

test('브라우저 예매와 좌석 전파 및 JWT 비노출', async ({
  browser,
  request,
}) => {
  const show = await seed(request);
  const buyerContext = await browser.newContext();
  const viewerContext = await browser.newContext();
  const organizerContext = await browser.newContext();
  const buyerGuard = await attachJwtGuard(buyerContext);
  const viewerGuard = await attachJwtGuard(viewerContext);
  const organizerGuard = await attachJwtGuard(organizerContext);
  const buyer = await buyerContext.newPage();
  const viewer = await viewerContext.newPage();
  const organizer = await organizerContext.newPage();
  try {
    await signupAndLogin(viewer);
    await signupAndLogin(buyer);
    await viewer.goto(`/performances/${show.performanceId}`);
    await viewer.getByRole('link', { name: '예매하기' }).click();
    await expect(
      viewer.getByRole('heading', { name: '좌석 선택' }),
    ).toBeVisible({ timeout: 45_000 });
    const seat1 = 'A구역 1열 1번, A석';
    const seat2 = 'A구역 1열 2번, A석';
    await expect(
      viewer.getByRole('button', { name: new RegExp(`${seat1}.*, 선택 가능`) }),
    ).toBeEnabled();

    await buyer.goto('/');
    await expect
      .poll(
        async () => {
          if (await buyer.getByRole('link', { name: show.title }).count())
            return true;
          await buyer.reload();
          return (
            (await buyer.getByRole('link', { name: show.title }).count()) > 0
          );
        },
        { timeout: 45_000, intervals: [1000, 3000, 5000] },
      )
      .toBe(true);
    await buyer.getByRole('link', { name: show.title }).click();
    await expect(buyer.getByText(/잔여 좌석 \d+ \/ 8/)).toBeVisible();
    await buyer.getByRole('link', { name: '예매하기' }).click();
    await expect(buyer.getByRole('heading', { name: '좌석 선택' })).toBeVisible(
      { timeout: 45_000 },
    );
    await buyer
      .getByRole('button', { name: new RegExp(`${seat1}.*, 선택 가능`) })
      .click();
    await buyer
      .getByRole('button', { name: new RegExp(`${seat2}.*, 선택 가능`) })
      .click();
    await buyer.getByRole('button', { name: '예매하기' }).click();
    await expect(
      buyer.getByRole('heading', { name: '결제', exact: true }),
    ).toBeVisible();
    await expect(
      viewer.getByRole('button', {
        name: new RegExp(`${seat1}.*, (선점됨|판매됨)`),
      }),
    ).toBeDisabled();
    await expect(
      viewer.getByRole('button', {
        name: new RegExp(`${seat2}.*, (선점됨|판매됨)`),
      }),
    ).toBeDisabled();
    await buyer.getByRole('button', { name: '결제하기' }).click();
    await expect(buyer.getByRole('heading', { name: '예매 완료' })).toBeVisible(
      { timeout: 45_000 },
    );
    await buyer.getByRole('link', { name: '마이페이지' }).last().click();
    const row = buyer.getByRole('listitem').filter({ hasText: show.title });
    await expect(row).toContainText('예매 확정');
    await expect(row).toContainText('결제 완료');
    await buyer.goto(`/performances/${show.performanceId}`);
    await expect(
      buyer.getByText('예매 내역 있음', { exact: false }),
    ).toBeVisible();

    await login(organizer, show.organizer.email, show.organizer.password);
    await organizer.goto('/organizer');
    await expect(
      organizer.getByRole('row').filter({ hasText: show.title }),
    ).toContainText('2 / 8');
    await buyerGuard.assertClean();
    await viewerGuard.assertClean();
    await organizerGuard.assertClean();
  } finally {
    await buyerContext.close();
    await viewerContext.close();
    await organizerContext.close();
  }
});
