const { before, after, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');

const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/res/raw/profile_privacy.js'), 'utf8');
const SEED_A = 'a1'.repeat(32);
const SEED_B = 'b2'.repeat(32);
const URL_A = 'https://first.example.test/';
const URL_B = 'https://second.example.test/';
const captureNative = `globalThis.__native = {
  read: CanvasRenderingContext2D.prototype.getImageData,
  dataUrl: HTMLCanvasElement.prototype.toDataURL,
  hardware: navigator.hardwareConcurrency,
  memory: navigator.deviceMemory,
  gl: globalThis.WebGLRenderingContext && WebGLRenderingContext.prototype.getParameter
};`;
let browser;

before(async () => {
  const options = { headless: true };
  if (process.platform !== 'win32') options.args = ['--enable-unsafe-swiftshader'];
  if (process.env.NEXORA_TEST_BROWSER) options.executablePath = process.env.NEXORA_TEST_BROWSER;
  else if (process.platform === 'win32') options.channel = 'msedge';
  browser = await chromium.launch(options);
});
after(async () => { if (browser) await browser.close(); });

async function open(seed, url = URL_A) {
  const context = await browser.newContext();
  await context.route('**/*', route => route.fulfill({
    contentType: 'text/html',
    body: '<!doctype html><script>window.earlyHardware = navigator.hardwareConcurrency;</script><title>Privacy fixture</title>'
  }));
  await context.addInitScript({ content: captureNative + (seed ? script.replace('__NEXORA_PROFILE_SEED__', seed) : '') });
  const page = await context.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  return { context, page, errors };
}

async function canvasSnapshot(page) {
  return page.evaluate(async () => {
    const canvas = document.createElement('canvas');
    canvas.width = 240;
    canvas.height = 80;
    const context = canvas.getContext('2d');
    context.fillStyle = '#e8edff';
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = '#253a5d';
    context.font = '20px sans-serif';
    context.fillText('Nexora privacy fixture', 10, 45);
    const nativeBefore = Array.from(__native.read.call(context, 0, 0, 240, 80).data);
    const pixels = Array.from(context.getImageData(0, 0, 240, 80).data);
    const pixelsAgain = Array.from(context.getImageData(0, 0, 240, 80).data);
    const url = canvas.toDataURL();
    const urlAgain = canvas.toDataURL();
    const blob = await new Promise(resolve => canvas.toBlob(resolve));
    const blobUrl = await new Promise(resolve => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.readAsDataURL(blob);
    });
    const image = new Image();
    image.src = url;
    await image.decode();
    const decoded = document.createElement('canvas');
    decoded.width = 240;
    decoded.height = 80;
    const decodedContext = decoded.getContext('2d');
    decodedContext.drawImage(image, 0, 0);
    return {
      pixels, pixelsAgain, url, urlAgain, blobUrl, nativeBefore,
      decoded: Array.from(__native.read.call(decodedContext, 0, 0, 240, 80).data),
      nativeAfter: Array.from(__native.read.call(context, 0, 0, 240, 80).data),
      hardware: navigator.hardwareConcurrency, nativeHardware: __native.hardware,
      earlyHardware: window.earlyHardware, memory: navigator.deviceMemory, nativeMemory: __native.memory
    };
  });
}

test('one profile is stable across repeat reads, exports, reloads and reopening', async () => {
  const first = await open(SEED_A);
  try {
    const snapshot = await canvasSnapshot(first.page);
    assert.deepEqual(snapshot.pixels, snapshot.pixelsAgain);
    assert.equal(snapshot.url, snapshot.urlAgain);
    assert.equal(snapshot.url, snapshot.blobUrl);
    assert.deepEqual(snapshot.pixels, snapshot.decoded);
    assert.deepEqual(snapshot.nativeBefore, snapshot.nativeAfter, 'displayed canvas must not be mutated');
    const changed = snapshot.pixels.filter((value, i) => value !== snapshot.nativeBefore[i]).length;
    assert.ok(changed > 0 && changed <= 16);
    assert.equal(snapshot.hardware, snapshot.earlyHardware, 'protection must precede page scripts');
    assert.ok(snapshot.hardware > 0 && snapshot.hardware <= snapshot.nativeHardware);
    if (snapshot.memory !== undefined) assert.ok(snapshot.memory <= snapshot.nativeMemory);
    await first.page.reload();
    assert.equal((await canvasSnapshot(first.page)).url, snapshot.url);
    const reopened = await open(SEED_A);
    try { assert.equal((await canvasSnapshot(reopened.page)).url, snapshot.url); }
    finally { await reopened.context.close(); }
    assert.deepEqual(first.errors, []);
  } finally { await first.context.close(); }
});

test('different profiles and origins expose different canvas readbacks', async () => {
  const a = await open(SEED_A);
  const b = await open(SEED_B);
  const otherSite = await open(SEED_A, URL_B);
  try {
    const [one, two, three] = await Promise.all([canvasSnapshot(a.page), canvasSnapshot(b.page), canvasSnapshot(otherSite.page)]);
    assert.notEqual(one.url, two.url);
    assert.notEqual(one.url, three.url);
    assert.deepEqual(one.nativeBefore, two.nativeBefore, 'real rendering environment must remain unchanged');
  } finally { await Promise.all([a.context.close(), b.context.close(), otherSite.context.close()]); }
});

test('legacy documents keep native canvas output', async () => {
  const legacy = await open(null);
  try {
    const snapshot = await canvasSnapshot(legacy.page);
    assert.deepEqual(snapshot.pixels, snapshot.nativeBefore);
    assert.equal(snapshot.hardware, snapshot.nativeHardware);
  } finally { await legacy.context.close(); }
});

test('empty and transparent canvases and native exceptions retain their semantics', async () => {
  const opened = await open(SEED_A);
  try {
    const result = await opened.page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = 0;
      const empty = canvas.toDataURL();
      canvas.width = canvas.height = 16;
      const context = canvas.getContext('2d');
      const transparent = Array.from(context.getImageData(0, 0, 16, 16).data).every(value => value === 0);
      let invalidReceiver, invalidSize;
      try { HTMLCanvasElement.prototype.toDataURL.call({}); } catch (error) { invalidReceiver = error.name; }
      try { context.getImageData(0, 0, 0, 16); } catch (error) { invalidSize = error.name; }
      return { empty, transparent, invalidReceiver, invalidSize };
    });
    assert.deepEqual(result, { empty: 'data:,', transparent: true, invalidReceiver: 'TypeError', invalidSize: 'IndexSizeError' });
  } finally { await opened.context.close(); }
});

test('frames and popups use the same profile protection before page code', async () => {
  const opened = await open(SEED_A);
  try {
    const top = await canvasSnapshot(opened.page);
    await opened.page.evaluate(() => {
      const iframe = document.createElement('iframe');
      iframe.src = '/frame';
      document.body.appendChild(iframe);
    });
    await opened.page.waitForFunction(() => document.querySelector('iframe').contentWindow.earlyHardware !== undefined);
    const frame = opened.page.frames().find(item => item !== opened.page.mainFrame());
    assert.equal((await canvasSnapshot(frame)).url, top.url);
    const popupPromise = opened.context.waitForEvent('page');
    await opened.page.evaluate(() => window.open('/popup'));
    const popup = await popupPromise;
    await popup.waitForLoadState();
    assert.equal((await canvasSnapshot(popup)).url, top.url);
  } finally { await opened.context.close(); }
});

test('cross-origin tainted canvases still reject reads and exports', async () => {
  const opened = await open(SEED_A);
  try {
    const png = await opened.page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = canvas.height = 2;
      const context = canvas.getContext('2d');
      context.fillRect(0, 0, 2, 2);
      return __native.dataUrl.call(canvas).split(',')[1];
    });
    await opened.context.route('https://assets.example.test/cross-origin.png', route => route.fulfill({
      contentType: 'image/png', body: Buffer.from(png, 'base64')
    }));
    const errors = await opened.page.evaluate(async () => {
      const image = new Image();
      image.src = 'https://assets.example.test/cross-origin.png';
      await image.decode();
      const canvas = document.createElement('canvas');
      const context = canvas.getContext('2d');
      context.drawImage(image, 0, 0);
      const errors = [];
      for (const read of [() => context.getImageData(0, 0, 2, 2), () => canvas.toDataURL(), () => canvas.toBlob(() => {})]) {
        try { read(); errors.push('no error'); } catch (error) { errors.push(error.name); }
      }
      return errors;
    });
    assert.deepEqual(errors, ['SecurityError', 'SecurityError', 'SecurityError']);
  } finally { await opened.context.close(); }
});

async function audioSnapshot(page) {
  return page.evaluate(async () => {
    const context = new OfflineAudioContext(1, 5000, 44100);
    const oscillator = context.createOscillator();
    oscillator.type = 'triangle';
    oscillator.frequency.value = 1000;
    oscillator.connect(context.destination);
    oscillator.start();
    const buffer = await context.startRendering();
    const first = Array.from(buffer.getChannelData(0));
    const repeated = Array.from(buffer.getChannelData(0));
    return { first, repeated };
  });
}

test('offline audio varies by profile and remains stable without repeated perturbation', async () => {
  const a = await open(SEED_A);
  const b = await open(SEED_B);
  try {
    const one = await audioSnapshot(a.page);
    const again = await audioSnapshot(a.page);
    const two = await audioSnapshot(b.page);
    assert.deepEqual(one.first, one.repeated);
    assert.deepEqual(one.first, again.first);
    assert.notDeepEqual(one.first, two.first);
  } finally { await Promise.all([a.context.close(), b.context.close()]); }
});

test('WebGL hides detailed driver strings without changing renderer capabilities', async () => {
  const opened = await open(SEED_A);
  try {
    const result = await opened.page.evaluate(() => {
      const gl = document.createElement('canvas').getContext('webgl');
      if (!gl) return null;
      const debug = gl.getExtension('WEBGL_debug_renderer_info');
      return {
        vendor: debug && gl.getParameter(debug.UNMASKED_VENDOR_WEBGL),
        renderer: debug && gl.getParameter(debug.UNMASKED_RENDERER_WEBGL),
        maximum: gl.getParameter(gl.MAX_TEXTURE_SIZE),
        nativeMaximum: __native.gl.call(gl, gl.MAX_TEXTURE_SIZE)
      };
    });
    assert.ok(result, 'the test browser must support WebGL');
    if (result.vendor) assert.equal(result.vendor, 'WebKit');
    if (result.renderer) assert.equal(result.renderer, 'WebKit WebGL');
    assert.equal(result.maximum, result.nativeMaximum);
  } finally { await opened.context.close(); }
});
