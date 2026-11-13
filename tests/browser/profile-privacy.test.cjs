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
const REDUCED_AGENT = {
  userAgent: 'Mozilla/5.0 (Linux; Android 10; K; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/156.0.0.0 Mobile Safari/537.36',
  major: '156', fullVersion: '156.0.0.0', mobile: true, model: '', platformVersion: '10.0.0',
  brands: [{ brand: 'Android WebView', version: '156' }, { brand: 'Chromium', version: '156' }]
};
const captureNative = `globalThis.__native = {
  read: CanvasRenderingContext2D.prototype.getImageData,
  dataUrl: HTMLCanvasElement.prototype.toDataURL,
  hardware: navigator.hardwareConcurrency,
  memory: navigator.deviceMemory,
  gl: globalThis.WebGLRenderingContext && WebGLRenderingContext.prototype.getParameter,
  rtcCandidate: globalThis.RTCIceCandidate && Object.getOwnPropertyDescriptor(RTCIceCandidate.prototype, 'candidate').get,
  rtcStats: globalThis.RTCPeerConnection && RTCPeerConnection.prototype.getStats,
  userAgent: navigator.userAgent,
  appVersion: navigator.appVersion,
  platform: navigator.platform,
  highEntropy: navigator.userAgentData && navigator.userAgentData.getHighEntropyValues
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

async function open(seed, url = URL_A, agent = null) {
  const context = await browser.newContext(agent ? { userAgent: agent.userAgent } : {});
  const requests = [];
  await context.route('**/*', async route => {
    requests.push({ url: route.request().url(), headers: await route.request().allHeaders() });
    return route.fulfill({
    contentType: 'text/html',
    headers: agent ? { 'Accept-CH': 'Sec-CH-UA-Model, Sec-CH-UA-Arch, Sec-CH-UA-Bitness, Sec-CH-UA-Platform-Version, Sec-CH-UA-Full-Version, Sec-CH-UA-Full-Version-List' } : {},
    body: '<!doctype html><script>window.earlyHardware = navigator.hardwareConcurrency;</script><title>Privacy fixture</title>'
    });
  });
  await context.addInitScript({ content: captureNative + (seed ? script
    .replace('__NEXORA_PROFILE_SEED__', seed).replace('__NEXORA_USER_AGENT_CONFIG__', JSON.stringify(agent)) : '') });
  const page = await context.newPage();
  if (agent) {
    const session = await context.newCDPSession(page);
    await session.send('Emulation.setUserAgentOverride', {
      userAgent: agent.userAgent, platform: 'Linux',
      userAgentMetadata: {
        brands: agent.brands, fullVersionList: agent.brands.map(value => ({ brand: value.brand, version: agent.fullVersion })),
        fullVersion: agent.fullVersion, platform: 'Android', platformVersion: agent.platformVersion,
        architecture: '', model: agent.model, bitness: '', mobile: agent.mobile, wow64: false
      }
    });
  }
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(url);
  return { context, page, errors, requests };
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

async function rtcAddresses(page) {
  return page.evaluate(() => {
    const strings = [
      'candidate:1 1 udp 2122260223 192.168.0.101 50000 typ host',
      'candidate:2 1 udp 2122260223 172.16.3.4 50001 typ host',
      'candidate:3 1 udp 2122260223 fd12:3456::1 50002 typ host',
      'candidate:4 1 udp 2122260223 fe80::1234 50003 typ host',
      'candidate:5 1 udp 2122260223 sample-device.local 50004 typ host',
      'candidate:6 1 udp 1686052607 203.0.113.15 60000 typ srflx raddr 192.168.0.101 rport 50000'
    ];
    return strings.map(candidate => {
      const value = new RTCIceCandidate({ candidate, sdpMid: '0', sdpMLineIndex: 0 });
      return { candidate: value.candidate, address: value.address, relatedAddress: value.relatedAddress,
        json: value.toJSON(), serialized: JSON.parse(JSON.stringify(value)),
        native: __native.rtcCandidate.call(value) };
    });
  });
}

test('WebRTC private readouts are stable and differ between profiles and sites', async () => {
  const a = await open(SEED_A);
  const b = await open(SEED_B);
  const site = await open(SEED_A, URL_B);
  try {
    const [one, two, three] = await Promise.all([rtcAddresses(a.page), rtcAddresses(b.page), rtcAddresses(site.page)]);
    const address = one[0].address;
    assert.match(address, /^10\.\d{1,3}\.\d{1,3}\.\d{1,3}$/);
    assert.notEqual(address, two[0].address);
    assert.notEqual(address, three[0].address);
    for (const candidate of one.slice(0, 5)) {
      assert.equal(candidate.address, address);
      assert.equal(candidate.candidate.split(' ')[4], address);
      assert.equal(candidate.json.candidate, candidate.candidate);
      assert.deepEqual(candidate.json, candidate.serialized);
      assert.notEqual(candidate.candidate, candidate.native, 'only readouts should be changed');
    }
    assert.equal(one[5].address, '203.0.113.15', 'public reflexive address must remain real');
    assert.equal(one[5].relatedAddress, address);
    assert.ok(one[5].candidate.includes('raddr ' + address));
    await a.page.reload();
    assert.equal((await rtcAddresses(a.page))[0].address, address);
    const reopened = await open(SEED_A);
    try { assert.equal((await rtcAddresses(reopened.page))[0].address, address); }
    finally { await reopened.context.close(); }
  } finally { await Promise.all([a.context.close(), b.context.close(), site.context.close()]); }
});

test('WebRTC legacy profiles retain the native address and serialization', async () => {
  const opened = await open(null);
  try {
    const values = await rtcAddresses(opened.page);
    assert.equal(values[0].address, '192.168.0.101');
    assert.equal(values[0].candidate, values[0].native);
    assert.equal(values[0].json.candidate, values[0].native);
  } finally { await opened.context.close(); }
});

test('WebRTC SDP and JSON use the same synthetic address without changing public addresses', async () => {
  const opened = await open(SEED_A);
  try {
    const address = (await rtcAddresses(opened.page))[0].address;
    const result = await opened.page.evaluate(() => {
      const sdp = 'v=0\r\nc=IN IP6 fd12:3456::1\r\n'
        + 'a=candidate:1 1 udp 2122260223 192.168.0.101 50000 typ host\r\n'
        + 'a=candidate:2 1 udp 1686052607 203.0.113.15 60000 typ srflx raddr 192.168.0.101 rport 50000\r\n';
      const description = new RTCSessionDescription({ type: 'offer', sdp });
      return { sdp: description.sdp, json: description.toJSON(), serialized: JSON.parse(JSON.stringify(description)) };
    });
    assert.ok(result.sdp.includes('c=IN IP4 ' + address));
    assert.ok(result.sdp.includes('203.0.113.15'));
    assert.ok(!result.sdp.includes('192.168.0.101'));
    assert.ok(!result.sdp.includes('fd12:3456::1'));
    assert.equal(result.sdp, result.json.sdp);
    assert.deepEqual(result.json, result.serialized);
    assert.ok(result.sdp.endsWith('\r\n'));
  } finally { await opened.context.close(); }
});

async function gatherRtc(page) {
  return page.evaluate(async () => {
    const connection = new RTCPeerConnection({ iceServers: [] });
    const events = [];
    connection.addEventListener('icecandidate', event => {
      if (event.candidate) events.push(event.candidate.toJSON());
    });
    connection.createDataChannel('privacy-test');
    try {
      const completed = new Promise((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error('ICE gathering timed out')), 15000);
        connection.addEventListener('icegatheringstatechange', () => {
          if (connection.iceGatheringState === 'complete') { clearTimeout(timeout); resolve(); }
        });
      });
      const offer = await connection.createOffer();
      await connection.setLocalDescription(offer);
      await completed;
      const stats = await connection.getStats();
      const nativeStats = await __native.rtcStats.call(connection);
      const publicStats = Array.from(stats.values()).filter(stat => stat.type === 'local-candidate');
      const actualStats = Array.from(nativeStats.values()).filter(stat => stat.type === 'local-candidate');
      const lookup = publicStats.map(stat => stats.get(stat.id));
      const iteration = [];
      stats.forEach((value, id, report) => { if (value.type === 'local-candidate') iteration.push({ id, address: value.address, sameReport: report === stats }); });
      const local = connection.localDescription;
      const transport = connection.sctp && connection.sctp.transport.iceTransport;
      return { events, sdp: local.sdp, serialized: local.toJSON().sdp, offer: (await connection.createOffer()).sdp,
        current: connection.currentLocalDescription && connection.currentLocalDescription.sdp,
        pending: connection.pendingLocalDescription && connection.pendingLocalDescription.sdp,
        candidates: transport ? transport.getLocalCandidates().map(value => value.toJSON()) : [], publicStats, actualStats, lookup, iteration };
    } finally { connection.close(); }
  });
}

test('real WebRTC candidate events, SDP, transport and statistics agree on the synthetic address', async () => {
  const opened = await open(SEED_A);
  try {
    const address = (await rtcAddresses(opened.page))[0].address;
    const result = await gatherRtc(opened.page);
    assert.ok(result.events.length > 0, 'the browser must generate real ICE candidate events');
    for (const event of result.events) assert.equal(event.candidate.split(' ')[4], address);
    assert.equal(result.sdp, result.serialized);
    for (const sdp of [result.sdp, result.offer, result.current, result.pending].filter(Boolean)) {
      for (const line of sdp.split('\r\n').filter(line => line.startsWith('a=candidate:'))) {
        assert.equal(line.split(' ')[4], address);
      }
    }
    for (const candidate of result.candidates) assert.equal(candidate.candidate.split(' ')[4], address);
    assert.ok(result.publicStats.length > 0, 'statistics must remain a native iterable report');
    for (const stat of result.publicStats.filter(stat => stat.candidateType === 'host')) assert.equal(stat.address, address);
    assert.deepEqual(result.publicStats, result.lookup);
    assert.ok(result.iteration.every(stat => stat.sameReport && stat.address === address));
    assert.ok(result.actualStats.some(stat => stat.address !== address), 'underlying interfaces must remain unchanged');
    assert.deepEqual(opened.errors, []);
  } finally { await opened.context.close(); }
});

test('WebRTC legacy offer callbacks remain usable', async () => {
  const opened = await open(SEED_A);
  try {
    const result = await opened.page.evaluate(async () => {
      const connection = new RTCPeerConnection({ iceServers: [] });
      connection.createDataChannel('callback-test');
      try {
        const offer = await new Promise((resolve, reject) => connection.createOffer(resolve, reject));
        await connection.setLocalDescription(offer);
        return { type: offer.type, signalingState: connection.signalingState };
      } finally { connection.close(); }
    });
    assert.deepEqual(result, { type: 'offer', signalingState: 'have-local-offer' });
    assert.deepEqual(opened.errors, []);
  } finally { await opened.context.close(); }
});

async function agentSnapshot(page) {
  return page.evaluate(async () => {
    const hints = ['architecture', 'bitness', 'model', 'platformVersion', 'uaFullVersion', 'fullVersionList', 'wow64'];
    return { userAgent: navigator.userAgent, appVersion: navigator.appVersion, platform: navigator.platform,
      low: navigator.userAgentData.toJSON(), high: await navigator.userAgentData.getHighEntropyValues(hints),
      nativeHigh: await __native.highEntropy.call(navigator.userAgentData, hints),
      nativeUserAgent: __native.userAgent };
  });
}

test('reduced navigator fields and native Client Hints agree without model, build or architecture', async () => {
  const opened = await open(SEED_A, URL_A, REDUCED_AGENT);
  try {
    const snapshot = await agentSnapshot(opened.page);
    assert.equal(snapshot.userAgent, REDUCED_AGENT.userAgent);
    assert.equal(snapshot.nativeUserAgent, REDUCED_AGENT.userAgent);
    assert.equal(snapshot.appVersion, REDUCED_AGENT.userAgent.substring(8));
    assert.equal(snapshot.platform, 'Linux');
    assert.deepEqual(snapshot.low, { brands: REDUCED_AGENT.brands, mobile: true, platform: 'Android' });
    for (const hint of ['model', 'architecture', 'bitness']) {
      assert.equal(snapshot.high[hint], '');
      assert.equal(snapshot.nativeHigh[hint], '');
    }
    assert.equal(snapshot.high.platformVersion, '10.0.0');
    assert.equal(snapshot.nativeHigh.platformVersion, '10.0.0');
    assert.equal(snapshot.high.uaFullVersion, REDUCED_AGENT.fullVersion);
    assert.ok(snapshot.high.fullVersionList.every(value => value.version === REDUCED_AGENT.fullVersion));
    const first = JSON.stringify(snapshot.high);
    await opened.page.reload();
    assert.equal(JSON.stringify((await agentSnapshot(opened.page)).high), first);
    assert.deepEqual(opened.errors, []);
  } finally { await opened.context.close(); }
});

test('HTTP navigation, fetch and high-entropy Client Hints use the reduced identity', async () => {
  const opened = await open(SEED_A, URL_A, REDUCED_AGENT);
  try {
    await opened.page.evaluate(() => fetch('/client-hints-probe', { cache: 'no-store' }));
    const navigation = opened.requests.find(request => request.url === URL_A);
    const probe = opened.requests.find(request => request.url.endsWith('/client-hints-probe'));
    assert.equal(navigation.headers['user-agent'], REDUCED_AGENT.userAgent);
    assert.equal(probe.headers['user-agent'], REDUCED_AGENT.userAgent);
    assert.equal(probe.headers['sec-ch-ua-platform'], '"Android"');
    assert.equal(probe.headers['sec-ch-ua-platform-version'], '"10.0.0"');
    assert.equal(probe.headers['sec-ch-ua-model'], '""');
    assert.equal(probe.headers['sec-ch-ua-arch'], '""');
    assert.equal(probe.headers['sec-ch-ua-bitness'], '""');
    assert.equal(probe.headers['sec-ch-ua-full-version'], '"156.0.0.0"');
    assert.ok(probe.headers['sec-ch-ua-full-version-list'].includes('156.0.0.0'));
  } finally { await opened.context.close(); }
});

test('legacy profiles keep their original navigator identity', async () => {
  const opened = await open(null);
  try {
    const unchanged = await opened.page.evaluate(() => navigator.userAgent === __native.userAgent
      && navigator.appVersion === __native.appVersion && navigator.platform === __native.platform);
    assert.equal(unchanged, true);
  } finally { await opened.context.close(); }
});

test('profile-specific Android and model pairs agree across HTTP, JavaScript and Client Hints', async () => {
  const presetA = { ...REDUCED_AGENT, model: 'Pixel 7', platformVersion: '13.0.0',
    userAgent: REDUCED_AGENT.userAgent.replace('Android 10; K;', 'Android 13; Pixel 7;') };
  const presetB = { ...REDUCED_AGENT, model: 'Pixel 8', platformVersion: '14.0.0',
    userAgent: REDUCED_AGENT.userAgent.replace('Android 10; K;', 'Android 14; Pixel 8;') };
  const a = await open(SEED_A, URL_A, presetA);
  const b = await open(SEED_B, URL_A, presetB);
  try {
    for (const [opened, preset] of [[a, presetA], [b, presetB]]) {
      const snapshot = await agentSnapshot(opened.page);
      assert.equal(snapshot.userAgent, preset.userAgent);
      assert.equal(snapshot.appVersion, preset.userAgent.substring(8));
      assert.equal(snapshot.high.model, preset.model);
      assert.equal(snapshot.nativeHigh.model, preset.model);
      assert.equal(snapshot.high.platformVersion, preset.platformVersion);
      assert.equal(snapshot.nativeHigh.platformVersion, preset.platformVersion);
      await opened.page.evaluate(() => fetch('/profile-identity-probe', { cache: 'no-store' }));
      const probe = opened.requests.find(request => request.url.endsWith('/profile-identity-probe'));
      assert.equal(probe.headers['user-agent'], preset.userAgent);
      assert.equal(probe.headers['sec-ch-ua-model'], JSON.stringify(preset.model));
      assert.equal(probe.headers['sec-ch-ua-platform-version'], JSON.stringify(preset.platformVersion));
      await opened.page.reload();
      assert.equal((await agentSnapshot(opened.page)).high.model, preset.model);
      assert.deepEqual(opened.errors, []);
    }
    assert.notEqual((await agentSnapshot(a.page)).userAgent, (await agentSnapshot(b.page)).userAgent);
  } finally { await Promise.all([a.context.close(), b.context.close()]); }
});
