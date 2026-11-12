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

    // Synthetic private IPv4 for WebRTC readouts, stable for this profile and origin.
    // This masks JavaScript metadata; it does not alter interfaces, routing or the public IP.
    const addressKey = number('webrtc-local-address');
    const localAddress = '10.' + ((addressKey >>> 16) & 255) + '.'
        + ((addressKey >>> 8) & 255) + '.' + ((addressKey & 255) % 254 + 1);
    const privateAddress = (value) => {
        if (typeof value !== 'string') return false;
        const host = value.toLowerCase();
        if (host.endsWith('.local')) return true;
        if (/^(fc|fd)[0-9a-f]{2}:/.test(host) || /^fe[89ab][0-9a-f]:/.test(host)) return true;
        if (host.startsWith('::ffff:')) return privateAddress(host.substring(7));
        const octets = host.split('.').map(Number);
        if (octets.length !== 4 || !octets.every(value => Number.isInteger(value) && value >= 0 && value <= 255)) return false;
        return octets[0] === 10 || (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31)
            || (octets[0] === 192 && octets[1] === 168) || (octets[0] === 169 && octets[1] === 254)
            || (octets[0] === 100 && octets[1] >= 64 && octets[1] <= 127);
    };
    const maskAddress = value => privateAddress(value) ? localAddress : value;
    const maskCandidate = (candidate) => {
        if (typeof candidate !== 'string' || !/^(a=)?candidate:/i.test(candidate)) return candidate;
        const fields = candidate.split(/\s+/);
        if (fields.length < 8) return candidate;
        if (fields[7] === 'host' || privateAddress(fields[4])) fields[4] = localAddress;
        for (let i = 8; i + 1 < fields.length; i++) {
            if (fields[i] === 'raddr') fields[i + 1] = maskAddress(fields[i + 1]);
        }
        return fields.join(' ');
    };
    const maskSdp = (sdp) => typeof sdp !== 'string' ? sdp : sdp.split(/(\r?\n)/).map(line => {
        if (/^a=candidate:/i.test(line)) return maskCandidate(line);
        const connection = /^(c=IN IP[46] )(.+)$/.exec(line);
        if (connection && privateAddress(connection[2])) return 'c=IN IP4 ' + localAddress;
        return line;
    }).join('');

    try {
        const prototype = globalThis.RTCIceCandidate && RTCIceCandidate.prototype;
        if (prototype) {
            const type = Object.getOwnPropertyDescriptor(prototype, 'type');
            for (const name of ['candidate', 'address', 'relatedAddress']) {
                const descriptor = Object.getOwnPropertyDescriptor(prototype, name);
                if (!descriptor || !descriptor.get || !descriptor.configurable) continue;
                const read = descriptor.get;
                Object.defineProperty(prototype, name, {
                    ...descriptor,
                    get() {
                        const value = Reflect.apply(read, this, []);
                        if (name === 'candidate') return maskCandidate(value);
                        const host = name === 'address' && type && type.get
                            && Reflect.apply(type.get, this, []) === 'host';
                        return host && value ? localAddress : maskAddress(value);
                    }
                });
            }
            const original = prototype.toJSON;
            if (original) prototype.toJSON = function (...args) {
                const value = Reflect.apply(original, this, args);
                value.candidate = maskCandidate(value.candidate);
                return value;
            };
        }
    } catch (_) { /* A locked interface does not disable other profile protections. */ }

    try {
        const prototype = globalThis.RTCSessionDescription && RTCSessionDescription.prototype;
        const descriptor = prototype && Object.getOwnPropertyDescriptor(prototype, 'sdp');
        if (descriptor && descriptor.get && descriptor.configurable) {
            const read = descriptor.get;
            Object.defineProperty(prototype, 'sdp', {
                ...descriptor, get() { return maskSdp(Reflect.apply(read, this, [])); }
            });
            const original = prototype.toJSON;
            if (original) prototype.toJSON = function (...args) {
                const value = Reflect.apply(original, this, args);
                value.sdp = maskSdp(value.sdp);
                return value;
            };
        }
        const Peer = globalThis.RTCPeerConnection || globalThis.webkitRTCPeerConnection;
        if (Peer) for (const name of ['createOffer', 'createAnswer']) {
            const original = Peer.prototype[name];
            const maskDescription = value => value == null ? value : ({ type: value.type, sdp: maskSdp(value.sdp) });
            Peer.prototype[name] = function (...args) {
                if (typeof args[0] === 'function') {
                    const success = args[0];
                    args[0] = value => success(maskDescription(value));
                }
                const result = Reflect.apply(original, this, args);
                return result && typeof result.then === 'function' ? result.then(maskDescription) : result;
            };
        }
    } catch (_) { /* Preserve native negotiation where a browser cannot patch the descriptors. */ }

    for (const name of ['RTCPeerConnection', 'RTCRtpSender', 'RTCRtpReceiver']) {
        try {
            const prototype = globalThis[name] && globalThis[name].prototype;
            const original = prototype && prototype.getStats;
            if (!original) continue;
            prototype.getStats = function (...args) {
                return Reflect.apply(original, this, args).then(report => {
                    const values = new Map();
                    for (const stat of report.values()) {
                        const value = { ...stat };
                        if (value.type === 'local-candidate') for (const field of ['address', 'ip', 'relatedAddress']) {
                            if (typeof value[field] !== 'string') continue;
                            value[field] = field !== 'relatedAddress' && value.candidateType === 'host'
                                ? localAddress : maskAddress(value[field]);
                        }
                        values.set(value.id, value);
                    }
                    // Some implementations recreate dictionaries on every read; editing a value
                    // from values() does not sanitize subsequent get(), entries() or forEach().
                    let masked;
                    masked = new Proxy(report, {
                        get(target, property) {
                            if (property === Symbol.iterator) return values[Symbol.iterator].bind(values);
                            if (property === 'size') return values.size;
                            if (['get', 'has', 'keys', 'values', 'entries'].includes(property)) return values[property].bind(values);
                            if (property === 'forEach') return (callback, receiver) =>
                                values.forEach((value, id) => callback.call(receiver, value, id, masked));
                            return Reflect.get(target, property, target);
                        }
                    });
                    return masked;
                });
            };
        } catch (_) { /* Older statistics interfaces may not support the promise-based API. */ }
    }

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
