import { expect, test, type Page } from '@playwright/test';
import {
  accounts,
  capture,
  expectNoConsoleErrors,
  gotoReady,
  login,
  newWatchedPage,
} from './support';

async function scheduleHrefs(page: Page): Promise<string[]> {
  await gotoReady(page, '/');
  const shows = await page
    .locator('a[href^="/performances/"]')
    .evaluateAll((els) =>
      Array.from(
        new Set(els.map((e) => (e as HTMLAnchorElement).getAttribute('href'))),
      ),
    );
  const result: string[] = [];
  for (const show of shows) {
    if (!show) continue;
    await gotoReady(page, show);
    const found = await page
      .locator('a[href^="/booking/"]')
      .evaluateAll((els) =>
        els.map((e) => (e as HTMLAnchorElement).getAttribute('href')!),
      );
    result.push(...found);
  }
  return result;
}

const available = (page: Page) =>
  page.getByRole('button', { name: /, 선택 가능$/ });

test('관람객 화면과 예매 흐름', async ({ browser }, testInfo) => {
  const { page, errors, context } = await newWatchedPage(browser, testInfo);
  const isMobile = testInfo.project.name === 'mobile';
  try {
    await login(page, accounts.user);
    await gotoReady(page, '/');
    await capture(page, testInfo, '10-home-loggedin');

    if (isMobile) {
      await page.getByRole('button', { name: '메뉴 열기' }).click();
      await page.getByRole('dialog').waitFor();
      await capture(page, testInfo, '11-header-menu');
      await page.keyboard.press('Escape');
    }

    // 선택 가능한 좌석이 2개 이상인 회차를 찾는다.
    let ready = false;
    for (const href of await scheduleHrefs(page)) {
      await page.goto(href);
      const seatsHeading = page.getByRole('heading', { name: '좌석 선택' });
      if (
        !(await seatsHeading
          .waitFor({ timeout: 45_000 })
          .then(() => true)
          .catch(() => false))
      )
        continue;
      await available(page)
        .first()
        .waitFor({ timeout: 15_000 })
        .catch(() => undefined);
      if ((await available(page).count()) >= 2) {
        ready = true;
        break;
      }
    }
    expect(ready, '선택 가능 좌석이 2개 이상인 회차').toBe(true);

    await available(page).nth(0).click();
    await available(page).nth(1).click();
    await expect(page.locator('[aria-pressed="true"]')).toHaveCount(2);
    await capture(page, testInfo, '12-seats');

    await page.getByRole('button', { name: '예매하기' }).click();
    await expect(
      page.getByRole('heading', { name: '결제', exact: true }),
    ).toBeVisible();
    // 쿠폰 패널은 마운트 후 목록을 불러오고, 불러오기 전에도 "없습니다"를 보여 준다.
    // 시드 관람객은 미사용 쿠폰이 있으므로 선택 상자가 뜰 때까지 기다린다.
    await expect(page.getByRole('button', { name: '쿠폰 적용' })).toBeVisible();
    await capture(page, testInfo, '13-payment');

    await page.getByRole('button', { name: '결제하기' }).click();
    await expect(page.getByRole('heading', { name: '예매 완료' })).toBeVisible({
      timeout: 45_000,
    });
    await capture(page, testInfo, '14-booking-done');

    await gotoReady(page, '/my');
    await capture(page, testInfo, '15-my');

    await gotoReady(page, '/coupons');
    await capture(page, testInfo, '16-coupons');

    expectNoConsoleErrors(errors);
  } finally {
    await context.close();
  }
});
