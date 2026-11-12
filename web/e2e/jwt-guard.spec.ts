import { expect, test } from '@playwright/test';
import { attachJwtGuard } from './support/jwt-guard';

test('JWT 검사기가 응답 본문의 토큰을 감지한다', async ({ context, page }) => {
  const guard = await attachJwtGuard(context);
  await page.route('**/jwt-guard-fixture', (route) =>
    route.fulfill({
      contentType: 'text/html',
      body: '<p>eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature</p>',
    }),
  );
  await page.goto('/jwt-guard-fixture');
  expect(await guard.violations()).toContainEqual(
    expect.stringContaining('응답 본문'),
  );
});
