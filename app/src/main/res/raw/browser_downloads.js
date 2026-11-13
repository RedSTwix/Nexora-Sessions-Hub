(function () {
  'use strict';
  if (window !== window.top || !/^https?:$/.test(location.protocol) || !window.__nexoraDownloads) return;
  const bridge = window.__nexoraDownloads;
  const nativeClick = HTMLAnchorElement.prototype.click;
  const nativeFetch = window.fetch.bind(window);
  let active = null;
  const CHUNK = 65536;

  bridge.onmessage = function (event) {
    let reply;
    try { reply = JSON.parse(event.data); } catch (_) { return; }
    if (!active || reply.id !== active.id) return;
    if (reply.error) active.error = reply.error;
    if (!active.waiter) return;
    const waiter = active.waiter;
    active.waiter = null;
    if (reply.error) waiter.reject(new Error(reply.error));
    else waiter.resolve(reply);
  };

  function send(message) {
    return new Promise((resolve, reject) => {
      if (active.error) { reject(new Error(active.error)); return; }
      active.waiter = { resolve, reject };
      bridge.postMessage(JSON.stringify(message));
    });
  }

  function sniff(bytes, declared) {
    const text = (offset, count) => String.fromCharCode(...bytes.slice(offset, offset + count));
    if (bytes.length >= 12 && text(4, 4) === 'ftyp') return text(8, 4) === 'qt  ' ? 'video/quicktime' : 'video/mp4';
    if (bytes.length >= 4 && bytes[0] === 0x1a && bytes[1] === 0x45 && bytes[2] === 0xdf && bytes[3] === 0xa3) return 'video/webm';
    if (bytes.length >= 8 && bytes[0] === 0x89 && text(1, 3) === 'PNG') return 'image/png';
    if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg';
    if (text(0, 5) === '%PDF-') return 'application/pdf';
    return declared || 'application/octet-stream';
  }

  async function save(url, name) {
    if (active) return;
    const request = { id: crypto.randomUUID ? crypto.randomUUID() : String(Date.now()) + Math.random(), token: '', waiter: null };
    let begun = false;
    active = request;
    try {
      // Start fetching synchronously, before the site immediately revokes its object URL.
      const response = await nativeFetch(url);
      const blob = await response.blob();
      const prefix = new Uint8Array(await blob.slice(0, 64).arrayBuffer());
      const mime = sniff(prefix, blob.type);
      begun = true;
      const ready = await send({ type: 'begin', id: request.id, name: name || 'arquivo', mime, size: blob.size });
      request.token = ready.token;
      let seq = 0;
      for (let offset = 0; offset < blob.size; offset += CHUNK) {
        const bytes = new Uint8Array(await blob.slice(offset, offset + CHUNK).arrayBuffer());
        let binary = '';
        for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
        await send({ type: 'chunk', id: request.id, token: request.token, seq: seq++, data: btoa(binary) });
      }
      await send({ type: 'end', id: request.id, token: request.token, seq });
    } catch (error) {
      try { bridge.postMessage(JSON.stringify({ type: 'error', id: request.id, token: request.token, reading: !begun })); } catch (_) {}
    } finally {
      if (active === request) active = null;
    }
  }

  function intercept(anchor) {
    if (!(anchor instanceof HTMLAnchorElement) || !anchor.href.startsWith('blob:')) return false;
    // Keep regular blob viewing/navigation intact; only intercept explicit downloads.
    if (!anchor.hasAttribute('download')) return false;
    save(anchor.href, anchor.download);
    return true;
  }

  HTMLAnchorElement.prototype.click = function () {
    if (!intercept(this)) return nativeClick.call(this);
  };
  document.addEventListener('click', event => {
    const anchor = event.target instanceof Element ? event.target.closest('a[download]') : null;
    if (anchor && intercept(anchor)) {
      event.preventDefault();
      event.stopImmediatePropagation();
    }
  }, true);

  // Native HTML5 video controls can request a blob download without an anchor.
  Object.defineProperty(window, '__nexoraSaveBlob', {
    value: function (url, name) { if (typeof url === 'string' && url.startsWith('blob:')) save(url, name); }
  });
})();
