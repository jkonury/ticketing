import { test } from '@playwright/test';
import {
  capture,
  expectNoConsoleErrors,
  gotoReady,
  newWatchedPage,
  accounts,
} from './support';

test('비로그인 화면', async ({ browser }, testInfo) => {
  const { page, errors, context } = await newWatchedPage(browser, testInfo);
  try {
    await gotoReady(page, '/');
    await capture(page, testInfo, '01-home');

    // 회차가 여러 개인 공연을 찾는다. 없으면 회차가 있는 첫 공연으로 대신한다.
    const hrefs = await page
      .locator('a[href^="/performances/"]')
      .evaluateAll((els) =>
        Array.from(
          new Set(
            els.map((e) => (e as HTMLAnchorElement).getAttribute('href')),
          ),
        ),
      );
    let target: string | null = null;
    for (const href of hrefs) {
      if (!href) continue;
      await gotoReady(page, href);
      const n = await page.locator('a[href^="/booking/"]').count();
      if (n >= 2) {
        target = href;
        break;
      }
      target ??= n >= 1 ? href : null;
    }
    await gotoReady(page, target ?? hrefs[0]!);
    await capture(page, testInfo, '02-performance');

    await gotoReady(page, '/login');
    await capture(page, testInfo, '03-login');

    await gotoReady(page, '/signup');
    await capture(page, testInfo, '04-signup');

    await page.goto('/login');
    await page.getByLabel('이메일').fill(accounts.user);
    await page.getByLabel('비밀번호').fill('wrong-password-000');
    await page.getByRole('button', { name: '로그인' }).click();
    await page.getByRole('alert').first().waitFor({ timeout: 15_000 });
    await capture(page, testInfo, '05-login-error');

    // 틀린 비밀번호 응답(401)은 브라우저가 리소스 오류로 기록할 수 있어 별도로 걸러지지 않는다.
    expectNoConsoleErrors(errors);
  } finally {
    await context.close();
  }
});
