import AxeBuilder from '@axe-core/playwright';
import { expect, test, type Page } from '@playwright/test';
import { randomUUID } from 'node:crypto';
import { registerMember, seed } from './support/seed';

const password = 'test-password-123';
const viewports = [
  { name: '모바일', width: 390, height: 844 },
  { name: '데스크톱', width: 1440, height: 900 },
];

async function expectNoViolations(page: Page) {
  const { violations } = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa'])
    .analyze();
  expect(
    violations.map(
      (v) => `${v.id}: ${v.nodes.map((n) => n.target).join(' | ')}`,
    ),
    `axe 위반 ${page.url()}`,
  ).toEqual([]);
}

// 결제 화면은 흐름이 길어 booking-flow에서 다루고 여기서는 제외한다.
for (const viewport of viewports) {
  test.describe(`접근성(axe) ${viewport.name}`, () => {
    test.use({ viewport: { width: viewport.width, height: viewport.height } });

    test('비로그인 화면에 위반이 없다', async ({ page, request }) => {
      const show = await seed(request);
      for (const path of [
        '/',
        `/performances/${show.performanceId}`,
        '/login',
        '/signup',
      ]) {
        await page.goto(path);
        await page.waitForLoadState('networkidle');
        await expectNoViolations(page);
      }
    });

    test('로그인 화면에 위반이 없다', async ({ page, request }) => {
      const show = await seed(request);
      const email = `a11y-${randomUUID()}@example.com`;
      await registerMember(request, { email, password, name: 'E2E 접근성' });
      await page.goto('/login');
      await page.getByLabel('이메일').fill(email);
      await page.getByLabel('비밀번호').fill(password);
      await page.getByRole('button', { name: '로그인' }).click();
      // 모바일은 로그아웃이 메뉴 안에 있어 로그인 화면을 벗어났는지로 확인한다.
      await expect(page).not.toHaveURL(/\/login/, { timeout: 20_000 });

      for (const path of ['/my', '/coupons']) {
        await page.goto(path);
        await page.waitForLoadState('networkidle');
        await expectNoViolations(page);
      }

      await page.goto(`/performances/${show.performanceId}`);
      await page.getByRole('link', { name: '예매하기' }).click();
      await expect(page.getByRole('heading', { name: '대기열' })).toBeVisible();
      await expectNoViolations(page);
      await expect(
        page.getByRole('heading', { name: '좌석 선택' }),
      ).toBeVisible({
        timeout: 45_000,
      });
      await expectNoViolations(page);
    });
  });
}
