const { before, after, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/res/raw/scroll_keyboard.js'), 'utf8');
let browser;
before(async () => {
  const options = { headless: true };
  if (process.env.NEXORA_TEST_BROWSER) options.executablePath = process.env.NEXORA_TEST_BROWSER;
  else if (process.platform === 'win32') options.channel = 'msedge';
  browser = await chromium.launch(options);
});
after(async () => { if (browser) await browser.close(); });

const fixture = `<!doctype html><meta name="viewport" content="width=device-width, initial-scale=1">
  <style>body {margin:0;height:2200px} input,textarea,#editor,#shadow {position:absolute;left:20px;width:260px;font-size:16px}
  input {top:20px;height:44px} textarea {top:100px;height:110px} #editor {top:240px;height:90px;overflow:auto}
  #shadow {top:350px;height:80px} #outside {position:absolute;top:460px;width:390px;height:1700px}</style>
  <input id="input" value="Texto preservado"><textarea id="textarea"></textarea>
  <div id="editor" contenteditable="true"></div><div id="shadow"></div><div id="outside">Página</div>
  <script>
  document.querySelector('#textarea').value = 'Linha de texto\\n'.repeat(60);
  document.querySelector('#editor').innerHTML = '<p>Linha de texto</p>'.repeat(30);
  document.querySelector('#shadow').attachShadow({mode:'open'}).innerHTML = '<textarea style="height:70px;width:250px">Texto</textarea>';
  </script>`;

async function open() {
  const context = await browser.newContext({ hasTouch: true, isMobile: true, viewport: { width: 390, height: 844 } });
  await context.route('**/*', route => route.fulfill({ contentType: 'text/html', body: fixture }));
  await context.addInitScript({ content: `window.keyboardRequests = [];
    window.__nexoraScrollKeyboard = {postMessage(value) { keyboardRequests.push(value); }};` + script });
  const page = await context.newPage();
  await page.goto('https://keyboard.example.test/');
  return { context, page };
}

async function swipe(page, x, y, dx, dy) {
  const session = await page.context().newCDPSession(page);
  try {
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x, y }] });
    for (let step = 1; step <= 5; step++)
      await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: x + dx * step / 5, y: y + dy * step / 5 }] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  } finally { await session.detach(); }
}

test('scrolling outside the editor dismisses once without losing typed text', async () => {
  const { context, page } = await open();
  try {
    await page.locator('#input').focus();
    await swipe(page, 340, 660, 0, -150);
    assert.deepEqual(await page.evaluate(() => keyboardRequests), ['hide']);
    assert.equal(await page.locator('#input').inputValue(), 'Texto preservado');
    assert.notEqual(await page.evaluate(() => document.activeElement.id), 'input');
    assert.ok(await page.evaluate(() => scrollY > 0));
  } finally { await context.close(); }
});

test('scrolling inside a multiline textarea preserves focus and native internal scrolling', async () => {
  const { context, page } = await open();
  try {
    await page.locator('#textarea').focus();
    await swipe(page, 150, 190, 0, -65);
    assert.deepEqual(await page.evaluate(() => keyboardRequests), []);
    assert.equal(await page.evaluate(() => document.activeElement.id), 'textarea');
    assert.ok(await page.locator('#textarea').evaluate(element => element.scrollTop > 0));
  } finally { await context.close(); }
});

test('scrolling a contenteditable editor also preserves editing', async () => {
  const { context, page } = await open();
  try {
    await page.locator('#editor').focus();
    await swipe(page, 150, 310, 0, -45);
    assert.deepEqual(await page.evaluate(() => keyboardRequests), []);
    assert.equal(await page.evaluate(() => document.activeElement.id), 'editor');
  } finally { await context.close(); }
});

test('inputs inside shadow roots retain the keyboard during an internal gesture', async () => {
  const { context, page } = await open();
  try {
    await page.locator('#shadow textarea').focus();
    await swipe(page, 150, 405, 0, -35);
    assert.deepEqual(await page.evaluate(() => keyboardRequests), []);
    assert.equal(await page.evaluate(() => document.querySelector('#shadow').shadowRoot.activeElement.tagName), 'TEXTAREA');
    await swipe(page, 340, 650, 0, -100);
    assert.deepEqual(await page.evaluate(() => keyboardRequests), ['hide']);
  } finally { await context.close(); }
});

test('small finger movements and taps do not request keyboard dismissal', async () => {
  const { context, page } = await open();
  try {
    await page.locator('#input').focus();
    await swipe(page, 340, 620, 2, 2);
    assert.deepEqual(await page.evaluate(() => keyboardRequests), []);
    await page.touchscreen.tap(340, 620);
    assert.deepEqual(await page.evaluate(() => keyboardRequests), []);
  } finally { await context.close(); }
});

test('two-finger zoom gestures leave keyboard dismissal alone', async () => {
  const { context, page } = await open();
  const session = await context.newCDPSession(page);
  try {
    await page.locator('#input').focus();
    await session.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 290, y: 550 }, { x: 340, y: 650 }] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: 270, y: 520 }, { x: 360, y: 680 }] });
    await session.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
    assert.deepEqual(await page.evaluate(() => keyboardRequests), []);
  } finally { await session.detach(); await context.close(); }
});

test('frames use their own editor boundaries', async () => {
  const { context, page } = await open();
  try {
    await page.evaluate(() => {
      const frame = document.createElement('iframe');
      frame.style.cssText = 'position:absolute;top:0;left:0;width:320px;height:450px;border:0;z-index:5';
      frame.src = 'https://frame.example.test/';
      document.body.append(frame);
    });
    await page.waitForFunction(() => window.frames.length === 1);
    const frame = page.frames().find(item => item !== page.mainFrame());
    await frame.waitForLoadState();
    await frame.locator('#textarea').focus();
    await swipe(page, 150, 190, 0, -60);
    assert.deepEqual(await frame.evaluate(() => keyboardRequests), []);
    await swipe(page, 300, 380, 0, -60);
    assert.deepEqual(await frame.evaluate(() => keyboardRequests), ['hide']);
    assert.notEqual(await frame.evaluate(() => document.activeElement.id), 'textarea');
  } finally { await context.close(); }
});
