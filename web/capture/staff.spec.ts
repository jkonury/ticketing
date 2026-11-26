import { test } from '@playwright/test';
import {
  accounts,
  capture,
  expectNoConsoleErrors,
  gotoReady,
  login,
  newWatchedPage,
} from './support';

test('주최자 화면', async ({ browser }, testInfo) => {
  const { page, errors, context } = await newWatchedPage(browser, testInfo);
  try {
    await login(page, accounts.organizer);
    await gotoReady(page, '/organizer');
    await capture(page, testInfo, '30-organizer');
    await gotoReady(page, '/organizer/new');
    await capture(page, testInfo, '31-organizer-new');
    expectNoConsoleErrors(errors);
  } finally {
    await context.close();
  }
});

test('운영자 화면', async ({ browser }, testInfo) => {
  const { page, errors, context } = await newWatchedPage(browser, testInfo);
  try {
    await login(page, accounts.admin);
    await gotoReady(page, '/admin/members');
    await capture(page, testInfo, '40-admin-members');
    await gotoReady(page, '/admin/coupons');
    await capture(page, testInfo, '41-admin-coupons');
    const href = await page
      .locator('a[href^="/admin/coupons/"]:not([href$="/new"])')
      .first()
      .getAttribute('href');
    if (!href) throw new Error('쿠폰 목록에 상세 링크가 없어요');
    await gotoReady(page, href);
    await capture(page, testInfo, '42-admin-coupon-detail');
    await gotoReady(page, '/admin/coupons/new');
    await capture(page, testInfo, '43-admin-coupon-new');
    await gotoReady(page, '/admin/audit-logs');
    await capture(page, testInfo, '44-admin-audit-logs');
    expectNoConsoleErrors(errors);
  } finally {
    await context.close();
  }
});
