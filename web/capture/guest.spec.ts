import { test } from '@playwright/test';
import {
  capture,
  expectNoConsoleErrors,
  gotoReady,
  newWatchedPage,
  accounts,
} from './support';

// 시각 비교 대상은 visual 옵션을 준 화면뿐이다. 마이페이지·주최자·관리자·좌석 상태·대기열은
// 누적 데이터나 시간에 따라 달라 제외한다(기준: capture/README.md).
test('비로그인 화면', async ({ browser }, testInfo) => {
  const { page, errors, context } = await newWatchedPage(browser, testInfo);
  try {
    await gotoReady(page, '/');
    // 시각 비교: 공연 데이터(포스터·제목·장소·일시)는 시드 시점에 따라 달라 가리고 레이아웃만 본다.
    // 공연 개수도 실행마다 달라 페이지 길이가 변하므로 첫 화면만 비교한다.
    await capture(page, testInfo, '01-home', {
      visual: {
        fullPage: false,
        mask: [
          page.locator('[data-visual-mask]'),
          page.locator('main li h2, main li p'),
        ],
      },
    });

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
    await capture(page, testInfo, '02-performance', {
      visual: {
        mask: [
          page.locator('[data-visual-mask]'),
          page.locator('main h1, main time, main p'),
        ],
      },
    });

    await gotoReady(page, '/login');
    await capture(page, testInfo, '03-login', { visual: {} });

    await gotoReady(page, '/signup');
    await capture(page, testInfo, '04-signup', { visual: {} });

    await page.goto('/login');
    await page.getByLabel('이메일').fill(accounts.user);
    await page.getByLabel('비밀번호').fill('wrong-password-000');
    await page.getByRole('button', { name: '로그인' }).click();
    // Next의 route announcer도 role=alert라서 폼 안의 오류로 좁힌다.
    await page.locator('form [role="alert"]').waitFor({ timeout: 15_000 });
    await capture(page, testInfo, '05-login-error', { visual: {} });

    // 틀린 비밀번호 응답(401)은 브라우저가 리소스 오류로 기록할 수 있어 별도로 걸러지지 않는다.
    expectNoConsoleErrors(errors);
  } finally {
    await context.close();
  }
});
