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
    expectNoConsoleErrors(errors);
  } finally {
    await context.close();
  }
});
