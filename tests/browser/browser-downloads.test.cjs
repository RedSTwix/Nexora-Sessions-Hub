const { before, after, test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { chromium } = require('playwright');
const script = fs.readFileSync(path.join(__dirname, '../../app/src/main/res/raw/browser_downloads.js'), 'utf8');
let browser;
before(async () => {
  const options = { headless: true };
  if (process.env.NEXORA_TEST_BROWSER) options.executablePath = process.env.NEXORA_TEST_BROWSER;
  else if (process.platform === 'win32') options.channel = 'msedge';
  browser = await chromium.launch(options);
});
after(async () => { if (browser) await browser.close(); });

async function open(cancelAt = -1) {
  const context = await browser.newContext();
  await context.route('**/*', route => route.fulfill({ contentType: 'text/html', body: '<!doctype html><title>Download fixture</title>' }));
  await context.addInitScript({ content: `
    window.downloadPackets = [];
    window.__nexoraDownloads = { postMessage(raw) {
      const value = JSON.parse(raw);
      downloadPackets.push(value);
      setTimeout(() => this.onmessage({data: JSON.stringify({ id: value.id, token: 'test-token',
        ...(value.type === 'chunk' && value.seq === ${cancelAt} ? {error:'Cancelado'} : {}) })}), 1);
    } };
  ` + script });
  const page = await context.newPage();
  await page.goto('https://downloads.example.test/');
  return { page, context };
}

test('saves an immediately revoked site blob with its MP4 signature and unchanged bytes', async () => {
  const { page, context } = await open();
  try {
    await page.evaluate(() => {
      const bytes = new Uint8Array(180000);
      for (let i = 0; i < bytes.length; i++) bytes[i] = i % 251;
      bytes.set([0x66, 0x74, 0x79, 0x70, 0x69, 0x73, 0x6f, 0x6d], 4);
      const url = URL.createObjectURL(new Blob([bytes]));
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = 'downloaded_file.bin';
      anchor.click();
      URL.revokeObjectURL(url);
    });
    await page.waitForFunction(() => downloadPackets.some(p => p.type === 'end'));
    const packets = await page.evaluate(() => downloadPackets);
    assert.equal(packets[0].mime, 'video/mp4');
    assert.equal(packets[0].name, 'downloaded_file.bin');
    assert.equal(packets[0].size, 180000);
    const chunks = packets.filter(p => p.type === 'chunk');
    assert.deepEqual(chunks.map(p => p.seq), [0, 1, 2]);
    assert.ok(chunks.every(p => Buffer.from(p.data, 'base64').length <= 65536));
    const bytes = Buffer.concat(chunks.map(p => Buffer.from(p.data, 'base64')));
    assert.equal(bytes.length, 180000);
    assert.equal(bytes.toString('ascii', 4, 8), 'ftyp');
    for (let i = 12; i < bytes.length; i++) assert.equal(bytes[i], i % 251);
  } finally { await context.close(); }
});

test('handles a real anchor click once and detects a WebM without a MIME type', async () => {
  const { page, context } = await open();
  try {
    await page.evaluate(() => {
      const anchor = document.createElement('a');
      anchor.href = URL.createObjectURL(new Blob([new Uint8Array([0x1a, 0x45, 0xdf, 0xa3, 0, 0, 0, 0])]));
      anchor.download = 'video.bin';
      anchor.textContent = 'Salvar';
      document.body.append(anchor);
    });
    await page.getByText('Salvar').click();
    await page.waitForFunction(() => downloadPackets.some(p => p.type === 'end'));
    const starts = await page.evaluate(() => downloadPackets.filter(p => p.type === 'begin'));
    assert.equal(starts.length, 1);
    assert.equal(starts[0].mime, 'video/webm');
  } finally { await context.close(); }
});

test('native HTML5 blob download requests use the same bounded transfer', async () => {
  const { page, context } = await open();
  try {
    await page.evaluate(() => __nexoraSaveBlob(URL.createObjectURL(new Blob(['%PDF-1.7\nfixture'])), 'arquivo.bin'));
    await page.waitForFunction(() => downloadPackets.some(p => p.type === 'end'));
    assert.equal(await page.evaluate(() => downloadPackets[0].mime), 'application/pdf');
  } finally { await context.close(); }
});

test('canceling stops chunks before completion and permits a retry', async () => {
  const { page, context } = await open(0);
  try {
    await page.evaluate(() => __nexoraSaveBlob(URL.createObjectURL(new Blob([new Uint8Array(150000)])), 'video'));
    await page.waitForFunction(() => downloadPackets.some(p => p.type === 'error'));
    const packets = await page.evaluate(() => downloadPackets);
    assert.equal(packets.filter(p => p.type === 'chunk').length, 1);
    assert.equal(packets.filter(p => p.type === 'end').length, 0);
    await page.evaluate(() => __nexoraSaveBlob(URL.createObjectURL(new Blob([])), 'vazio'));
    await page.waitForFunction(() => downloadPackets.some(p => p.type === 'end'));
    assert.equal(await page.evaluate(() => downloadPackets.filter(p => p.type === 'begin').length), 2);
  } finally { await context.close(); }
});

test('HTTP links and subframes do not use the blob bridge', async () => {
  const { page, context } = await open();
  try {
    await page.evaluate(() => {
      __nexoraSaveBlob('https://example.test/ordinary.mp4', 'video');
      const frame = document.createElement('iframe');
      frame.src = 'https://frame.example.test/';
      document.body.append(frame);
    });
    await page.waitForFunction(() => document.querySelector('iframe').contentWindow !== null);
    const frame = page.frames().find(f => f !== page.mainFrame());
    await frame.waitForLoadState();
    assert.equal(await frame.evaluate(() => typeof window.__nexoraSaveBlob), 'undefined');
    assert.deepEqual(await page.evaluate(() => downloadPackets), []);
  } finally { await context.close(); }
});
