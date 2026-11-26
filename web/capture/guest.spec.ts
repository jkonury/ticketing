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
    // 시각 비교: 목록 카드는 공연 수·순서·제목 줄 수가 실행마다 달라 마스크 박스도 흔들리므로,
    // 머리글(제목·설명) 영역만 비교한다. 카드 배치는 캡처 이미지로 눈으로 확인한다.
    await capture(page, testInfo, '01-home', {
      visual: {
        target: page.getByRole('heading', { level: 1 }).locator('xpath=../..'),
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
    // 시각 비교가 같은 화면을 보도록 시드 공연(회차 2개, 등급 4개)을 이름으로 고정한다. E2E가 만든
    // 공연이 목록에 섞이면 먼저 만나는 공연이 실행마다 달라진다. 없을 때만 아래 탐색으로 대신한다.
    let target: string | null = await page
      .locator('a[href^="/performances/"]', { hasText: '라스트 발레 나이트' })
      .first()
      .getAttribute('href', { timeout: 2_000 })
      .catch(() => null);
    for (const href of target ? [] : hrefs) {
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
    // 시각 비교에서 제외: 포스터·제목·일시를 가리면 남는 것이 가격표뿐이고, CI에서 비교 실행과
    // 기준 생성 실행의 페이지 높이가 달라지는 현상을 원인 없이 겪었다(capture/README.md).
    await capture(page, testInfo, '02-performance');

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
