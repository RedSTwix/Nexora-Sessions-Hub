/* Partial document-realm protection. Workers and native network characteristics are unchanged. */
(() => {
    'use strict';
    const secret = '__NEXORA_PROFILE_SEED__';
    let origin = location.origin;
    if (origin === 'null') {
        try { origin = parent !== self ? parent.location.origin : opener ? opener.location.origin : ''; }
        catch (_) { origin = ''; }
    }
    // The private profile seed is scoped to the document origin; it is never exposed as a web ID.
    let key = 2166136261;
    for (const c of secret + '|' + origin) key = Math.imul(key ^ c.charCodeAt(0), 16777619) >>> 0;
    const number = (salt) => {
        let value = key;
        for (const c of salt) value = Math.imul(value ^ c.charCodeAt(0), 16777619) >>> 0;
        value ^= value >>> 16;
        value = Math.imul(value, 0x7feb352d);
        value ^= value >>> 15;
        return value >>> 0;
    };

    // Coarse, plausible resource buckets, limited to the capacity actually reported by the browser.
    for (const [name, buckets] of [['hardwareConcurrency', [1, 2, 4, 8]], ['deviceMemory', [1, 2, 4, 8]]]) {
        try {
            const prototype = Object.getPrototypeOf(navigator);
            const descriptor = Object.getOwnPropertyDescriptor(prototype, name);
            if (!descriptor || !descriptor.get || !descriptor.configurable) continue;
            const original = descriptor.get;
            const actual = Reflect.apply(original, navigator, []);
            const choices = buckets.filter(value => value <= actual);
            if (!choices.length) continue;
            const value = choices[number(name) % choices.length];
            Object.defineProperty(prototype, name, {
                ...descriptor,
                get() { Reflect.apply(original, this, []); return value; }
            });
        } catch (_) { /* A restricted API must not prevent other protections from being installed. */ }
    }

    // Touch at most 16 opaque RGB samples. Displayed canvases and transparent pixels are unchanged.
    const perturb = (image, salt) => {
        if (!(image.data instanceof Uint8ClampedArray)) return image;
        const pixels = image.data.length / 4;
        if (!pixels) return image;
        const used = new Set();
        for (let i = 0; i < Math.min(16, pixels); i++) {
            const index = number(salt + ':' + i) % pixels;
            if (used.has(index) || image.data[index * 4 + 3] !== 255) continue;
            used.add(index);
            const channel = index * 4 + number('channel:' + i) % 3;
            image.data[channel] ^= 1;
        }
        return image;
    };

    try {
        const prototype = CanvasRenderingContext2D.prototype;
        const originalRead = prototype.getImageData;
        const originalDataUrl = HTMLCanvasElement.prototype.toDataURL;
        const originalBlob = HTMLCanvasElement.prototype.toBlob;
        const maxPixels = 4 * 1024 * 1024;
        const saltFor = (width, height) => 'canvas:' + width + ':' + height;
        prototype.getImageData = function (...args) {
            const image = Reflect.apply(originalRead, this, args);
            return image.width * image.height <= maxPixels
                ? perturb(image, saltFor(image.width, image.height)) : image;
        };
        const noisyCopy = (canvas) => {
            try {
                if (!(canvas instanceof HTMLCanvasElement) || !canvas.width || !canvas.height
                        || canvas.width * canvas.height > maxPixels) return null;
                const copy = document.createElement('canvas');
                copy.width = canvas.width;
                copy.height = canvas.height;
                const context = copy.getContext('2d');
                context.drawImage(canvas, 0, 0);
                const image = Reflect.apply(originalRead, context, [0, 0, copy.width, copy.height]);
                perturb(image, saltFor(copy.width, copy.height));
                context.putImageData(image, 0, 0);
                return copy;
            } catch (_) { return null; }
        };
        HTMLCanvasElement.prototype.toDataURL = function (...args) {
            // Preserve native receiver validation, tainted-canvas errors and empty-canvas handling.
            const original = Reflect.apply(originalDataUrl, this, args);
            const copy = noisyCopy(this);
            return copy ? Reflect.apply(originalDataUrl, copy, args) : original;
        };
        if (originalBlob) HTMLCanvasElement.prototype.toBlob = function (...args) {
            return Reflect.apply(originalBlob, noisyCopy(this) || this, args);
        };
    } catch (_) { /* Canvas may be unavailable in a restricted frame. */ }

    // Suppress detailed driver/hardware strings without inventing a different GPU's capabilities.
    for (const name of ['WebGLRenderingContext', 'WebGL2RenderingContext']) {
        try {
            const prototype = globalThis[name] && globalThis[name].prototype;
            if (!prototype) continue;
            const original = prototype.getParameter;
            prototype.getParameter = function (...args) {
                const value = Reflect.apply(original, this, args);
                if (value !== null && args[0] === 37445) return 'WebKit';
                if (value !== null && args[0] === 37446) return 'WebKit WebGL';
                return value;
            };
        } catch (_) { /* Keep rendering APIs working even if a descriptor is locked. */ }
    }

    // Perturb offline analysis only. Live audio, microphone capture and playback are not intercepted.
    try {
        const Offline = globalThis.OfflineAudioContext || globalThis.webkitOfflineAudioContext;
        if (Offline) {
            const original = Offline.prototype.startRendering;
            const read = AudioBuffer.prototype.getChannelData;
            const factor = 1 + ((number('offline-audio') % 1024) + 1) * 1e-8;
            Offline.prototype.startRendering = function (...args) {
                const result = Reflect.apply(original, this, args);
                return result.then(buffer => {
                    if (buffer.length * buffer.numberOfChannels <= 4 * 1024 * 1024) {
                        for (let channel = 0; channel < buffer.numberOfChannels; channel++) {
                            const samples = Reflect.apply(read, buffer, [channel]);
                            for (let i = 0; i < samples.length; i++) samples[i] *= factor;
                        }
                    }
                    return buffer;
                });
            };
        }
    } catch (_) { /* Unsupported audio APIs are left untouched. */ }
})();
