import { expect, test, Page } from '@playwright/test';
import { readFileSync } from 'node:fs';

const CUBE = { name: 'test-cube-20mm.stl', mimeType: 'model/stl', buffer: readFileSync(new URL('../../site-src/assets/test-cube-20mm.stl', import.meta.url)) };

/** In-app navigation (a hash change), as the app's own links do. page.goto would reload and drop the open plate. */
const go = (page: Page, hash: string) => page.evaluate((h) => { location.hash = h; }, hash);

async function addCube(page: Page) {
  await page.goto('/#/prepare/models');
  await page.locator('input[type=file][aria-label="Choose models"]').setInputFiles(CUBE);
  await expect(page.getByText('test-cube-20mm').first()).toBeVisible();
}

test('the page is cross-origin isolated, so the threaded engine can run', async ({ page }) => {
  await page.goto('/');
  expect(await page.evaluate(() => crossOriginIsolated)).toBe(true);
});

test('slices a model in the browser, then saves, reopens and exports the project', async ({ page }) => {
  await addCube(page);
  await go(page, '#/prepare/slice');
  await page.getByTestId('slice').click();
  await expect(page.getByText(/Sliced in [\d.]+ s on this device/)).toBeVisible({ timeout: 150_000 });
  await expect(page.locator('.metrics')).toContainText('100'); // layers: 20 mm at 0.2 mm
  await expect(page.locator('.metrics')).toContainText(/3\.[67] g/);

  await page.getByTestId('save').click();
  await expect(page.getByText('Saved in this browser').first()).toBeVisible();
  await page.reload(); // the project was saved in this browser; a reload starts empty
  await go(page, '#/');
  await page.getByRole('list', { name: 'Your projects' }).getByRole('button', { name: 'Open' }).first().click();
  await expect(page.getByText('Saved in this browser')).toBeVisible();
  await expect(page.getByTestId('save')).toBeEnabled(); // the plate has its model again

  await go(page, '#/prepare/send');
  await expect(page.getByText('Slice the plate first.')).toBeVisible(); // reopening never pretends to have G-code
  await go(page, '#/prepare/slice');
  await page.getByTestId('slice').click();
  await expect(page.getByText(/Sliced in/)).toBeVisible({ timeout: 150_000 });
  await go(page, '#/prepare/send');
  const download = page.waitForEvent('download');
  await page.getByRole('button', { name: 'Download sliced file' }).click();
  const gcode = readFileSync(await (await download).path()!, 'utf8');
  expect(gcode).toContain('; total layer number: 100');
});

test('slices for another maker\'s printer with nothing connected', async ({ page }) => {
  await addCube(page);
  await go(page, '#/prepare/printer');
  await page.getByTestId('change-profile').click();
  await page.getByLabel('Find a printer profile').fill('prusa mk4');
  await page.getByRole('list', { name: 'Printer profiles' }).getByRole('button').first().click();
  await expect(page.getByTestId('profile-name')).toContainText(/Prusa|MK4/i);
  await go(page, '#/prepare/slice');
  await page.getByTestId('slice').click();
  await expect(page.getByText(/Sliced in/)).toBeVisible({ timeout: 150_000 });
  await expect(page.locator('.metrics')).toContainText('100');
});

test('cancelling a slice leaves the project unchanged', async ({ page }) => {
  await addCube(page);
  await go(page, '#/prepare/slice');
  await page.getByTestId('slice').click();
  await page.getByTestId('cancel-slice').click();
  await expect(page.getByText('Slicing was cancelled. Nothing changed.')).toBeVisible();
  await page.getByTestId('slice').click();
  await expect(page.getByText(/Sliced in/)).toBeVisible({ timeout: 150_000 });
});
