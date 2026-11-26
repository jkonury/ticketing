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

// 예매 캡처가 첫 회차에서 입장 토큰(10분)을 받아 두므로 마지막 회차를 쓴다.
// 같은 회차를 10분 안에 다시 캡처하면 바로 입장되므로 CAPTURE_QUEUE_SHOW(공연 순번)·CAPTURE_QUEUE_SCHEDULE(회차 링크 순번)로 바꾼다.
async function queueScheduleHref(page: Page): Promise<string> {
  await gotoReady(page, '/');
  const show = await page
    .locator('a[href^="/performances/"]')
    .nth(Number(process.env.CAPTURE_QUEUE_SHOW ?? 0))
    .getAttribute('href');
  await gotoReady(page, show!);
  const href = await page
    .locator('a[href^="/booking/"]')
    .nth(Number(process.env.CAPTURE_QUEUE_SCHEDULE ?? -1))
    .getAttribute('href');
  return href!;
}

// 이전 실행의 대기 항목이 남아 있으면 순번이 어긋나므로 진입 전에 지운다.
async function leaveQueue(page: Page, href: string) {
  const scheduleId = href.split('/').pop();
  await page.evaluate(
    (id) => fetch(`/api/queues/${id}/entries/me`, { method: 'DELETE' }),
    scheduleId,
  );
}

test('대기 순번 화면', async ({ browser }, testInfo) => {
  const main = await newWatchedPage(browser, testInfo);
  const others = [];
  try {
    await login(main.page, accounts.user);
    const href = await queueScheduleHref(main.page);
    // 같은 회차 대기열에 먼저 2명을 넣는다. 1명씩 로그인하는 사이 앞사람이 입장해 버리므로 로그인을 모두 끝낸 뒤 한꺼번에 들어간다.
    for (const email of [accounts.organizer, accounts.admin]) {
      const other = await newWatchedPage(browser, testInfo);
      others.push(other);
      await login(other.page, email);
    }
    await Promise.all(
      others.map(async (other) => {
        await leaveQueue(other.page, href);
        await other.page.goto(href);
      }),
    );
    await leaveQueue(main.page, href);
    await main.page.goto(href);
    await expect(main.page.getByText('내 대기 순번')).toBeVisible({
      timeout: 30_000,
    });
    // 순번 숫자의 등장 모션(300ms)이 끝난 뒤 찍는다.
    await main.page.waitForTimeout(600);
    await capture(main.page, testInfo, '20-queue');
    expectNoConsoleErrors(main.errors);
  } finally {
    for (const o of others) await o.context.close();
    await main.context.close();
  }
});
