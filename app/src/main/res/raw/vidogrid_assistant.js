(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(root);
  else {
    if (root !== root.top || location.origin !== 'https://vidogrid.ai') return;
    if (root.__nexoraSiteAssistant) return;
    const integration = factory(root);
    let opened = false, acceptedLimit = 5;
    const supported = () => /^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname);
    const controller = {
      visible: () => opened && !!root.__nexoraVidoAssistant,
      acceptedLimit: () => acceptedLimit,
      setAcceptedLimit(value) {
        if (!Number.isInteger(value) || value < 0 || value > 2147483647) return false;
        acceptedLimit = value; root.__nexoraVidoAssistant?.setAcceptedLimit(value); return true;
      },
      toggle() {
        if (controller.visible()) { controller.hide(); return true; }
        if (!supported() || !document.body || ![...document.querySelectorAll('button')]
          .some(button => /^generate with grok\b/i.test(button.textContent.trim()))) return false;
        opened = true; integration.install(); return true;
      },
      hide() { opened = false; root.__nexoraVidoAssistant?.destroy(); root.__nexoraVidoPreload?.restore(); },
      destroy() { controller.hide(); if (root.__nexoraSiteAssistant === controller) delete root.__nexoraSiteAssistant; }
    };
    root.__nexoraSiteAssistant = controller;
  }
})(globalThis, function (root) {
  'use strict';

  class RetryState {
    constructor(limit = 5) { this.limit = limit; this.runStarted = false; this.active = false; this.tries = 0; this.accepted = 0; this.lastAccepted = null; this.readyAccepted = null;
      this.phase = 'off'; this.due = null; this.inFlight = false; this.reason = ''; }
    stop(reason, phase = 'paused') {
      this.active = false; this.due = null; this.phase = phase; this.reason = reason;
    }
    start(snapshot, now) {
      this.active = true; this.runStarted = true; this.tries = 0; this.accepted = 0;
      this.lastAccepted = snapshot.accepted ? snapshot.acceptedToken : null;
      this.readyAccepted = this.lastAccepted;
      this.inFlight = false; this.due = null; this.reason = ''; this.initialAllowed = true;
      this.manualErrorToken = snapshot.otherError ? snapshot.errorToken : null;
      if (snapshot.challenge || snapshot.request?.pending || (snapshot.processing && !snapshot.accepted)) this.markAttempt(snapshot, now, true);
      return this.observe(snapshot, now);
    }
    resume(snapshot, now) {
      this.active = true; this.inFlight = false; this.due = null; this.reason = ''; this.initialAllowed = true;
      this.manualErrorToken = snapshot.otherError ? snapshot.errorToken : null;
      this.readyAccepted = snapshot.accepted ? snapshot.acceptedToken : null;
      if (snapshot.challenge || snapshot.request?.pending || (snapshot.processing && !snapshot.accepted)) this.markAttempt(snapshot, now, true);
      return this.observe(snapshot, now);
    }
    setLimit(value) {
      if (!Number.isInteger(value) || value < 0 || value > 2147483647) return false;
      this.limit = value;
      if (this.runStarted && value > 0 && this.accepted >= value)
        this.stop('Meta de ' + value + ' solicitações aceitas atingida.', 'limit');
      else if (this.phase === 'limit') this.stop('Limite atualizado. Retome para continuar o ciclo.');
      return true;
    }
    markAttempt(snapshot, now, joinPending = false) {
      this.manualErrorToken = null;
      this.inFlight = true; this.started = now; this.priorError = snapshot.errorToken;
      this.requestFloor = (snapshot.request?.id || 0) - (joinPending && snapshot.request?.pending ? 1 : 0);
      this.priorCancelledVerification = snapshot.cancelledVerification || 0;
      this.errorCleared = !snapshot.errorToken; this.due = null; this.readyAccepted = null;
    }
    observe(snapshot, now) {
      if (!this.active) return this.phase;
      if (this.limit > 0 && this.accepted >= this.limit) {
        this.stop('Meta de ' + this.limit + ' solicitações aceitas atingida.', 'limit'); return this.phase;
      }
      if (!snapshot.route) { this.stop('Você saiu da página de geração.'); return this.phase; }
      if (snapshot.hidden) { this.stop('Página em segundo plano. Ative novamente ao voltar.'); return this.phase; }
      if (this.inFlight && snapshot.cancelledVerification > this.priorCancelledVerification) {
        this.stop(snapshot.cancelledVerificationReason || 'A verificação do site foi fechada. Retome para tentar novamente.'); return this.phase;
      }
      if (snapshot.success && !snapshot.accepted) { this.stop('O site exibiu um vídeo no resultado.', 'complete'); return this.phase; }
      if (this.inFlight && !snapshot.errorToken && !snapshot.challenge && snapshot.processing) this.errorCleared = true;
      if (snapshot.challenge) { this.phase = 'checking'; this.due = null; return this.phase; }
      if (snapshot.accepted && snapshot.acceptedToken && snapshot.acceptedToken !== this.lastAccepted) {
        this.lastAccepted = snapshot.acceptedToken; this.readyAccepted = snapshot.acceptedToken;
        this.accepted++; this.inFlight = false;
        if (this.limit > 0 && this.accepted >= this.limit) {
          this.stop('Meta de ' + this.limit + ' solicitações aceitas atingida.', 'limit'); return this.phase;
        }
      }
      if (snapshot.processing && !snapshot.accepted) { this.phase = 'processing'; this.due = null; return this.phase; }
      if (this.inFlight) {
        const request = snapshot.request, currentRequest = request && request.id > this.requestFloor;
        const confirmed503 = currentRequest && !request.pending && request.status === 503 && snapshot.failure503;
        const newFailure = !(currentRequest && request.pending)
          && (confirmed503 || (snapshot.errorToken && (this.errorCleared || snapshot.errorToken !== this.priorError)));
        if (!newFailure) {
          // A visible old 503 is not proof that the current request has finished.
          // Stop an ambiguous wait instead of repeating an unconfirmed request.
          if (!(currentRequest && request.pending) && snapshot.failure503 && now - this.started >= 90000) {
            this.stop('503 na tela, mas sem confirmação de nova resposta. Pausado para evitar envio duplicado.');
          } else this.phase = 'waiting';
          return this.phase;
        }
        this.inFlight = false;
      }
      // An explicit Activate/Resume may start one new request over an old
      // failure. It never turns that previous failure into an automatic retry.
      const manualRetry = !this.inFlight && this.initialAllowed && snapshot.otherError
        && this.manualErrorToken !== null && snapshot.errorToken === this.manualErrorToken;
      if (snapshot.otherError && !manualRetry) { this.stop('O site exibiu uma falha diferente de 503.'); return this.phase; }
      if (!snapshot.failure503 && !(snapshot.accepted && snapshot.acceptedToken === this.readyAccepted)
        && !(this.initialAllowed && snapshot.initialReady) && !manualRetry) {
        this.phase = 'waiting'; this.due = null; return this.phase;
      }
      if (!snapshot.free) { this.stop('Custo zero não confirmado. Use o botão original do site.'); return this.phase; }
      if (!snapshot.enabled) { this.phase = 'waiting'; this.due = null; return this.phase; }
      if (this.due === null) this.due = now;
      this.phase = manualRetry ? 'retry' : snapshot.failure503 ? 'countdown' : 'accepted'; return this.phase;
    }
    takeAttempt(snapshot, now) {
      this.observe(snapshot, now);
      if (!this.active || !['countdown','accepted','retry'].includes(this.phase) || this.due === null || now < this.due) return false;
      this.tries++; this.initialAllowed = false; this.markAttempt(snapshot, now); this.phase = 'waiting'; return true;
    }
  }

  // Observe completion metadata only. Bodies, headers, responses and verification
  // values stay with the site's original request; streams are never consumed here.
  function observeGenerationRequests(isActive, changed) {
    let latest = null, sequence = 0, disposed = false;
    const matches = (method, value) => {
      try {
        if (String(method).toUpperCase() !== 'POST') return false;
        const base = root.__NUXT__?.config?.public?.api?.imagenproService;
        if (typeof base !== 'string') return false;
        const service = new URL(base, root.location.href), url = new URL(value, root.location.href);
        const prefix = service.pathname.replace(/\/$/, '');
        return service.protocol === 'https:' && url.origin === service.origin
          && url.pathname.startsWith(prefix + '/')
          && /^\/video-gen\/grok-(?:imagine-video-1\.5(?:-lite)?|stream)\/?$/.test(url.pathname.slice(prefix.length));
      } catch { return false; }
    };
    const begin = () => {
      if (disposed || !isActive()) return null;
      latest = {id: ++sequence, pending: true, status: null}; changed(); return latest;
    };
    const finish = (item, status) => {
      if (!disposed && item) { item.pending = false; item.status = status; changed(); }
    };
    const originalFetch = root.fetch;
    const wrappedFetch = typeof originalFetch === 'function' ? new Proxy(originalFetch, {apply(target, receiver, args) {
      const [input, options] = args;
      const match = matches(options?.method ?? input?.method ?? 'GET', input instanceof root.Request ? input.url : input);
      const promise = Reflect.apply(target, receiver, args);
      const item = match ? begin() : null;
      if (item) promise.then(response => finish(item, response.status), () => finish(item, 0));
      return promise;
    }}) : null;
    if (wrappedFetch) root.fetch = wrappedFetch;
    const prototype = root.XMLHttpRequest?.prototype, originalOpen = prototype?.open, originalSend = prototype?.send;
    const targets = new WeakMap(), pending = new Set();
    const wrappedOpen = prototype && new Proxy(originalOpen, {apply(target, receiver, args) {
      const result = Reflect.apply(target, receiver, args);
      targets.set(receiver, matches(args[0], args[1])); return result;
    }});
    const wrappedSend = prototype && new Proxy(originalSend, {apply(target, receiver, args) {
      const item = targets.get(receiver) ? begin() : null;
      const cleanup = () => { receiver.removeEventListener('loadend', completed); pending.delete(cleanup); };
      const completed = () => { finish(item, receiver.status); cleanup(); };
      if (item) { receiver.addEventListener('loadend', completed); pending.add(cleanup); }
      try { return Reflect.apply(target, receiver, args); }
      catch (error) { finish(item, 0); cleanup(); throw error; }
    }});
    if (prototype) { prototype.open = wrappedOpen; prototype.send = wrappedSend; }
    return {
      snapshot: () => latest ? {...latest} : null,
      restore() {
        disposed = true;
        if (root.fetch === wrappedFetch) root.fetch = originalFetch;
        if (prototype?.open === wrappedOpen) prototype.open = originalOpen;
        if (prototype?.send === wrappedSend) prototype.send = originalSend;
        for (const cleanup of pending) cleanup();
      }
    };
  }

  function install() {
    if (root !== root.top || location.origin !== 'https://vidogrid.ai' || !/^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname)) return {installed:false};
    if (root.__nexoraVidoAssistant) return root.__nexoraVidoAssistant.status();
    const document = root.document;
    const machine = new RetryState(root.__nexoraSiteAssistant?.acceptedLimit() ?? 5);
    let disposed = false, scheduledFrame = null;
    const requests = observeGenerationRequests(() => machine.active, scheduleTick);
    const errorIds = new WeakMap(); let sequence = 0, dispatching = false, signature = '', resultEpoch = 0, previousCreated = '';
    let cachedButton = null, cachedLabel = null, cachedOutput = null, cachedPrompt = null, cachedForm = null;
    const host = document.createElement('div');
    host.id = 'nexora-vidogrid-assistant';
    host.style.cssText = 'position:fixed!important;left:12px!important;right:12px!important;bottom:12px!important;z-index:2147483646!important;pointer-events:auto!important;';
    const shadow = host.attachShadow({mode:'closed'});
    shadow.innerHTML = `<style>
      :host{all:initial}*{box-sizing:border-box}section{font:13px/1.4 system-ui,sans-serif;color:#eef9fb;background:#0a1b23f5;border:1px solid #26505c;border-radius:16px;padding:13px 14px;box-shadow:0 8px 30px #0009}
      header{display:flex;align-items:center;gap:7px;color:#25eee2;font-size:10px;letter-spacing:1px;font-weight:750}header span{flex:1}b{display:block;margin-top:5px;font-size:15px;letter-spacing:0}p{margin:4px 0 9px;color:#b3cbd3;font-size:12px}nav{display:flex;gap:8px;align-items:center}button{font:600 12px system-ui;min-height:36px;border-radius:10px;border:1px solid #31505a;padding:7px 11px;background:#112c36;color:#eaf9fc;cursor:pointer}button.primary{background:#25eee2;color:#032126;border-color:#25eee2;flex:1}button.small{min-height:26px;padding:2px 8px;background:transparent}i{width:6px;height:6px;border-radius:100%;background:#25eee2}section[data-phase="checking"] i,section[data-phase="countdown"] i{background:#eac35c}#preload{font-size:11px;color:#74d8ce}section[data-collapsed="true"] p{display:none}
    </style><section data-phase="off"><header><i></i><span>NEXORA · VIDOGRID</span><button class="small" id="settings" aria-label="Configurações do assistente">⚙</button><button class="small" id="collapse" aria-label="Recolher painel">−</button><button class="small" id="close" aria-label="Fechar painel e parar repetição">×</button></header><b id="title"></b><p id="detail"></p><p id="preload"></p><nav><button class="primary" id="toggle"></button><button id="result">Ver resultado</button></nav></section>`;
    const $ = selector => shadow.querySelector(selector);
    let panelPress = null;
    const panelButtons = new Set(shadow.querySelectorAll('button'));
    const insideButton = (button, event) => {
      const rect = button.getBoundingClientRect();
      return event.clientX >= rect.left && event.clientX <= rect.right
        && event.clientY >= rect.top && event.clientY <= rect.bottom;
    };
    const pointerButton = event => event.composedPath().find(element => panelButtons.has(element) && insideButton(element,event));
    shadow.addEventListener('pointerdown', event => {
      panelPress = {id:event.pointerId,button:pointerButton(event),released:false};
    }, true);
    shadow.addEventListener('pointerup', event => {
      if (panelPress?.id === event.pointerId) panelPress.released = !!panelPress.button && pointerButton(event) === panelPress.button;
    }, true);
    shadow.addEventListener('pointercancel', () => { panelPress = null; }, true);
    // Make empty panel space its own touch target and keep its clicks out of
    // the site's handlers. Mobile touch adjustment must not activate a nearby button.
    $('section').addEventListener('click', event => event.stopPropagation());
    function panelAction(selector, action) {
      const button = $(selector);
      button.addEventListener('click', event => {
        event.stopPropagation();
        const press = panelPress; panelPress = null;
        if (event.isTrusted && event.detail > 0
          && (!press?.released || press.button !== button || !insideButton(button,event))) return;
        action(event);
      });
    }
    let previousView = '';
    const clock = () => root.performance.now();
    const visible = element => !!(element?.isConnected && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden'
      && !element.closest('[aria-hidden="true"], [hidden]'));
    const text = element => (element?.textContent || '').trim();
    const renderedText = element => (element?.innerText || element?.textContent || '').trim();
    function refreshElements() {
      if (!cachedButton?.isConnected || !/^generate with grok\b/i.test(text(cachedButton)))
        cachedButton = [...document.querySelectorAll('button')].find(item => /^generate with grok\b/i.test(text(item)) && visible(item));
      if (!cachedLabel?.isConnected || text(cachedLabel).toLowerCase() !== 'output')
        cachedLabel = [...document.querySelectorAll('span')].find(item => text(item).toLowerCase() === 'output');
      cachedOutput = cachedLabel?.parentElement?.parentElement;
      if (!cachedPrompt?.isConnected) cachedPrompt = document.querySelector('textarea[placeholder*="Grok"]') || document.querySelector('textarea');
      cachedForm = cachedPrompt?.closest('aside') || cachedPrompt?.parentElement;
    }
    function siteJobIdentity() {
      try {
        // Read only the current generation's public result metadata. The site
        // omits Created At from the rendering view, but retains its server UUID.
        const store = document.getElementById('__nuxt')?.__vue_app__?.config?.globalProperties?.$pinia?._s?.get?.('textToVideoStore');
        if (!store || !('textToVideoResult' in store)) return null;
        const result = store.textToVideoResult;
        const confirmed = typeof result?.uuid === 'string' && result.uuid.length > 0 && result.uuid.length <= 256
          && [0,1,2].includes(result.status);
        // Before acceptance, the site also uses status=1 for a local placeholder.
        // An actual job UUID is required; a spinner or percentage is insufficient.
        return {token: confirmed ? 'job:' + result.uuid : ''};
      } catch { return null; }
    }
    function snapshot() {
      refreshElements();
      const button = visible(cachedButton) ? cachedButton : null, output = cachedOutput;
      const error = output && [...output.querySelectorAll('p')].find(item => /^generation failed$/i.test(text(item)) && visible(item));
      if (error && !errorIds.has(error)) errorIds.set(error, ++sequence);
      const errorText = error ? renderedText(error.parentElement) : '';
      const challenge = !!root.__nexoraVidoPreload?.isSiteVerificationPending?.()
        || [...document.querySelectorAll('[role="dialog"], [data-state="open"]')]
          .some(item => visible(item) && /verify you.re human|complete the verification|security check/i.test(text(item)))
        || [...document.querySelectorAll('iframe')].some(item => !item.closest('[data-nexora-precheck]')
          && visible(item) && /^https:\/\/challenges\.cloudflare\.com\//i.test(item.getAttribute('src') || ''));
      const enabled = !!button && !button.disabled && button.getAttribute('aria-disabled') !== 'true';
      const outputText = renderedText(output);
      const processing = !!button && (!enabled || /generating|submitting|processing|queued|rendering|creating video/i.test(outputText)) && !error;
      const success = !!output && !error && [...output.querySelectorAll('video')].some(item => visible(item) && !!(item.currentSrc || item.getAttribute('src') || item.querySelector('source[src]')));
      const created = output && [...output.querySelectorAll('dt')].find(item => /^created at$/i.test(text(item)));
      const stamp = text(created?.nextElementSibling);
      if (previousCreated && !stamp) resultEpoch++;
      previousCreated = stamp;
      const job = siteJobIdentity();
      const acceptedToken = job ? job.token : stamp ? stamp + '|' + resultEpoch : '';
      const accepted = !!output && !error && !!acceptedToken && visible(output)
        && (/\bqueued\b|\brendering\b|\bcreating video\b/i.test(outputText) || success);
      const free = !!button && /this generation will cost:\s*0\s*credits\b/i.test(renderedText(button.parentElement));
      const initialReady = enabled && !error && !challenge && !processing && !accepted
        && !!document.querySelector('textarea')?.value.trim();
      return {route: location.origin === 'https://vidogrid.ai' && /^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname),
        hidden: document.hidden, button, output, challenge, processing, success, enabled, free, accepted, acceptedToken,
        initialReady, request: requests.snapshot(),
        cancelledVerification: root.__nexoraVidoPreload?.cancelledSiteVerifications?.() || 0,
        cancelledVerificationReason: root.__nexoraVidoPreload?.cancelledSiteVerificationReason?.() || '',
        errorToken: error ? errorIds.get(error) : null, failure503: !!error && /\bstatus\s*503\b/i.test(errorText),
        otherError: !!error && !/\bstatus\s*503\b/i.test(errorText)};
    }
    function noteResultRevision(changes) {
      if (!machine.inFlight || !changes.some(change => touches(change, cachedOutput))) return;
      const output = cachedOutput;
      // Capture a short-lived empty result before batching the visual update.
      // Two jobs may otherwise retain the same displayed creation timestamp.
      if (previousCreated) {
        const created = output && [...output.querySelectorAll('dt')].find(item => /^created at$/i.test(text(item)));
        if (!text(created?.nextElementSibling)) { resultEpoch++; previousCreated = ''; }
      }
      const error = output && [...output.querySelectorAll('p')].find(item =>
        /^generation failed$/i.test(text(item)) && visible(item));
      if (!error) return;
      const panel = error.parentElement;
      // The site can rewrite a result in the same DOM nodes in a single task.
      // The usual snapshot never sees the old error disappear in that case.
      const updated = changes.some(change => {
        if (change.type === 'characterData') return panel.contains(change.target.parentNode);
        if (change.type !== 'childList') return false;
        if (panel.contains(change.target)) return true;
        return [...change.addedNodes].some(node =>
          node === panel || (typeof node.contains === 'function' && node.contains(error)));
      });
      if (updated) errorIds.set(error, ++sequence);
    }
    function formSignature() {
      const form = cachedForm || document;
      const fields = [...form.querySelectorAll('textarea,select,input:not([type="hidden"])')]
        .filter(item => !/turnstile|captcha/i.test(item.name || ''))
        .map(item => [item.type,item.value,item.checked]);
      const choices = [...form.querySelectorAll('[role="tab"][aria-selected="true"],button[data-state="on"],button[aria-pressed="true"]')].map(item => text(item));
      return JSON.stringify([location.pathname, fields, choices]);
    }
    function touches(change, element) {
      return !!element && (element.contains(change.target) || (change.type === 'childList'
        && [...change.addedNodes,...change.removedNodes].some(node => node === element || node.contains(element))));
    }
    function relevantChange(change) {
      if (!cachedButton?.isConnected || !cachedOutput?.isConnected) return true;
      if (touches(change,cachedButton) || touches(change,cachedOutput) || touches(change,cachedForm)) return true;
      const element = change.target.nodeType === 1 ? change.target : change.target.parentElement;
      if (element?.closest('[data-nexora-precheck]')) return false;
      if (element?.closest('[role="dialog"],#grok-turnstile-modal')) return true;
      return change.type === 'childList' && [...change.addedNodes].some(node => node.nodeType === 1
        && !node.matches('[data-nexora-precheck]')
        && (node.matches('[role="dialog"],#grok-turnstile-modal') || node.querySelector('[role="dialog"],#grok-turnstile-modal')));
    }
    function scheduleTick() {
      if (disposed || scheduledFrame !== null) return;
      scheduledFrame = root.requestAnimationFrame(() => { scheduledFrame = null; tick(); });
    }
    function render(now) {
      const elapsed = machine.inFlight ? Math.floor((now-machine.started)/1000) : 0;
      const labels = {off:'Repetir após falha 503',checking:'Aguardando a verificação',processing:'Solicitação em andamento',waiting:'Aguardando resposta do site',
        countdown:'Servidor ocupado · 503',retry:'Iniciando nova tentativa',accepted:'Solicitação aceita · enviando outra',paused:'Repetição pausada',limit:'Meta de aceitas atingida',complete:'Vídeo disponível'};
      const title = labels[machine.phase] || labels.waiting;
      const totals = `${machine.tries} enviadas · ${machine.accepted}${machine.limit ? '/' + machine.limit : ''} aceitas`;
      const detail = machine.phase === 'off' ? (machine.limit ? `Pausa após ${machine.limit} aceitas · configure na engrenagem.` : 'Repete após 503 ou novo aceite · sem limite de aceitas.')
        : machine.phase === 'countdown' ? `Nova tentativa ao receber a falha · ${machine.tries} realizadas`
        : machine.phase === 'checking' ? `${elapsed}s na checagem · ${totals}`
        : machine.inFlight && machine.active ? `${elapsed}s de espera · ${totals}`
        : machine.active ? totals
        : machine.reason;
      const prepared = root.__nexoraVidoPreload?.status();
      const currentCheck = prepared?.assigned ? (prepared.currentNeedsInteraction
        ? 'Checagem da vez · aguardando sua interação' : 'Checagem antecipada em uso')
        : prepared?.awaitingSiteVerification ? 'Verificação normal do site em andamento' : '';
      const nextCheck = prepared?.approvalReady ? 'Próxima checagem pronta · aguardando a solicitação atual'
        : prepared?.needsInteraction ? 'Próxima checagem aguardará sua interação'
        : prepared?.preparing ? 'Preparando a próxima checagem'
        : machine.active ? 'Antecipação da próxima checagem ligada' : 'A antecipação acompanha a repetição';
      const preparation = [currentCheck,nextCheck].filter(Boolean).join(' · ');
      const view = JSON.stringify([title,detail,machine.phase,machine.active,preparation]);
      if (view === previousView) return; previousView = view;
      $('#title').textContent = title; $('#detail').textContent = detail;
      $('#preload').textContent = preparation;
      $('section').dataset.phase = machine.phase;
      $('#toggle').textContent = machine.active ? 'Pausar repetição' : machine.phase === 'limit' ? 'Iniciar novo ciclo'
        : machine.runStarted ? 'Retomar repetição' : 'Ativar repetição';
    }
    function tick() {
      if (disposed) return;
      if (!host.isConnected) { destroy(); return; }
      if (!/^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname)) { root.__nexoraSiteAssistant?.hide(); return; }
      const now = clock();
      if (!machine.active) { root.__nexoraVidoPreload?.sync(); render(now); return; }
      const state = snapshot();
      if (machine.active && signature !== formSignature()) machine.stop('O formulário foi alterado. Confira e ative novamente.');
      machine.observe(state, now);
      if (machine.active) root.__nexoraInstallVidoPreload?.();
      root.__nexoraVidoPreload?.sync();
      if (machine.takeAttempt(state, now)) {
        // Invoke only the site's generation button. The challenge iframe remains under the site's control.
        dispatching = true;
        try { state.button.click(); }
        catch { machine.stop('Não foi possível acionar o botão do site.'); }
        finally { dispatching = false; }
      }
      render(now);
    }
    function toggle() {
      if (machine.active) machine.stop('Pausado por você.');
      else { refreshElements(); signature = formSignature();
        if (machine.runStarted && machine.phase !== 'limit') machine.resume(snapshot(), clock());
        else machine.start(snapshot(), clock());
      }
      tick();
    }
    function pause() { if (machine.phase !== 'limit') machine.stop('Pausado. Retome pelo painel para continuar.'); root.__nexoraVidoPreload?.sync(); render(clock()); }
    function setAcceptedLimit(value) { machine.setLimit(value); root.__nexoraVidoPreload?.sync(); render(clock()); }
    function onClick(event) {
      if (dispatching || !machine.active || event.composedPath().includes(host)) return;
      if (!event.composedPath().some(element => element?.tagName === 'BUTTON' && /^generate with grok\b/i.test(text(element)))) return;
      const state = snapshot();
      if (state.button && event.composedPath().includes(state.button)) machine.markAttempt(state, clock());
    }
    function onChange(event) {
      if (machine.active && !event.composedPath().includes(host)
        && event.target.matches?.('textarea,select,input:not([type="hidden"])')
        && !/turnstile|captcha/i.test(event.target.name || '')) {
        machine.stop('O formulário foi alterado. Confira e ative novamente.'); render(clock());
        root.__nexoraVidoPreload?.sync();
      }
    }
    function destroy() {
      if (disposed) return;
      disposed = true;
      if (scheduledFrame !== null) root.cancelAnimationFrame(scheduledFrame);
      root.clearInterval(timer); machine.stop('Assistente removido.');
      requests.restore();
      root.__nexoraVidoPreload?.restore();
      observer.disconnect();
      document.removeEventListener('click', onClick, true);
      document.removeEventListener('input', onChange, true);
      document.removeEventListener('change', onChange, true);
      document.removeEventListener('visibilitychange', tick);
      root.removeEventListener('nexora-vidogrid-change', scheduleTick);
      host.remove();
      if (root.__nexoraVidoAssistant === api) delete root.__nexoraVidoAssistant;
    }
    panelAction('#toggle', toggle);
    $('#settings').disabled = typeof root.__nexoraSiteSettings?.postMessage !== 'function';
    panelAction('#settings', () => { pause(); root.__nexoraSiteSettings?.postMessage('settings'); });
    panelAction('#close', () => root.__nexoraSiteAssistant?.hide());
    panelAction('#collapse', () => { const section = $('section');
      const collapsed = section.dataset.collapsed !== 'true'; section.dataset.collapsed = String(collapsed);
      $('#collapse').textContent = collapsed ? '+' : '−'; });
    panelAction('#result', () => snapshot().output?.scrollIntoView({behavior:'smooth',block:'start'}));
    document.addEventListener('click', onClick, true);
    document.addEventListener('input', onChange, true);
    document.addEventListener('change', onChange, true);
    document.addEventListener('visibilitychange', tick);
    root.addEventListener('nexora-vidogrid-change', scheduleTick);
    document.documentElement.appendChild(host);
    const timer = root.setInterval(() => {
      if (machine.active) scheduleTick();
      else if (!/^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname)) root.__nexoraSiteAssistant?.hide();
    }, 500);
    const api = {destroy, toggle, pause, setAcceptedLimit, isActive: () => machine.active, status: () => ({active:machine.active,phase:machine.phase,tries:machine.tries,accepted:machine.accepted,acceptedLimit:machine.limit,
      remaining:machine.due===null?null:Math.max(0,Math.ceil((machine.due-clock())/1000)),inFlight:machine.inFlight,reason:machine.reason,
      snapshot:(()=>{const state=snapshot();return {failure503:state.failure503,challenge:state.challenge,enabled:state.enabled,free:state.free,success:state.success,accepted:state.accepted};})()})};
    root.__nexoraVidoAssistant = api;
    const observer = new MutationObserver(changes => {
      if (!host.isConnected) { destroy(); return; }
      if (!/^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname)) { root.__nexoraSiteAssistant?.hide(); return; }
      if (!machine.active) return;
      noteResultRevision(changes);
      if (changes.some(relevantChange)) scheduleTick();
    });
    observer.observe(document.body, {subtree:true,childList:true,characterData:true,attributes:true,
      attributeFilter:['disabled','aria-disabled','hidden','aria-hidden','data-state']});
    tick();
    return {installed:true,automaticRetries:'off',scope:'current profile main page only'};
  }
  return {RetryState, install};
});
