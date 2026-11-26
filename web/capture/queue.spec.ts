import { expect, test, type Page } from '@playwright/test';
import {
  accounts,
  capture,
  expectNoConsoleErrors,
  gotoReady,
  login,
  newWatchedPage,
} from './support';

// realtime이 `./scripts/dev/run.sh --all --slow-queue`로 떠 있을 때만 의미가 있다.
test.skip(process.env.CAPTURE_QUEUE !== '1', 'CAPTURE_QUEUE=1일 때만 실행');

async function firstScheduleHref(page: Page): Promise<string> {
  await gotoReady(page, '/');
  const show = await page
    .locator('a[href^="/performances/"]')
    .first()
    .getAttribute('href');
  await gotoReady(page, show!);
  const href = await page
    .locator('a[href^="/booking/"]')
    .first()
    .getAttribute('href');
  return href!;
}

test('대기 순번 화면', async ({ browser }, testInfo) => {
  const main = await newWatchedPage(browser, testInfo);
  const others = [];
  try {
    await login(main.page, accounts.user);
    const href = await firstScheduleHref(main.page);
    // 같은 회차 대기열에 먼저 3명을 넣는다.
    for (let i = 0; i < 3; i++) {
      const other = await newWatchedPage(browser, testInfo);
      others.push(other);
      await login(other.page, i === 0 ? accounts.organizer : accounts.admin);
      await other.page.goto(href);
    }
    await main.page.goto(href);
    await expect(main.page.getByText(/대기 순번/)).toBeVisible({
      timeout: 30_000,
    });
    await capture(main.page, testInfo, '20-queue');
    expectNoConsoleErrors(main.errors);
  } finally {
    for (const o of others) await o.context.close();
    await main.context.close();
  }
});
