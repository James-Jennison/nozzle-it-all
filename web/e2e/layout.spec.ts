import { expect, test } from '@playwright/test';

// Every page at phone and desktop widths: one h1, every control has a name, nothing scrolls sideways.
// Screenshots are kept as visual evidence in test-results/evidence.
const pages = [['projects', '#/'], ['prepare', '#/prepare/models'], ['prepare-printer', '#/prepare/printer'], ['printers', '#/printers'], ['add-printer', '#/printers/add'], ['settings', '#/settings']];
const sizes = [['phone', 390, 844], ['desktop', 1440, 900]] as const;

for (const [size, width, height] of sizes) {
  for (const [name, hash] of pages) {
    test(`${name} at ${size} width is usable`, async ({ page }, info) => {
      await page.setViewportSize({ width, height });
      await page.goto(`/${hash}`);
      await expect(page.locator('main h1')).toHaveCount(1);
      const unnamed = await page.evaluate(() => [...document.querySelectorAll('button, a[href], input:not([type=hidden]), select, textarea')]
        .filter((el) => (el as HTMLElement).offsetParent !== null)
        .filter((el) => { const e = el as HTMLElement; const id = e.getAttribute('id'); const label = id ? document.querySelector(`label[for="${id}"]`) : null;
          return !(e.getAttribute('aria-label') || e.getAttribute('aria-labelledby') || e.innerText?.trim() || e.getAttribute('title') || label || e.closest('label')); })
        .map((el) => el.outerHTML.slice(0, 120)));
      expect(unnamed, 'controls without an accessible name').toEqual([]);
      expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1), 'no horizontal scroll').toBe(true);
      await page.screenshot({ path: `test-results/evidence/${info.project.name}-${size}-${name}.png`, fullPage: true });
    });
  }
}

test('dark and light themes both render', async ({ page }) => {
  for (const scheme of ['dark', 'light'] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto('/#/');
    const bg = await page.evaluate(() => getComputedStyle(document.body).backgroundColor);
    expect(bg).not.toBe('rgba(0, 0, 0, 0)');
    await page.screenshot({ path: `test-results/evidence/theme-${scheme}.png` });
  }
});
