(function (root, factory) {
  if (typeof module === 'object' && module.exports) module.exports = factory(root);
  else {
    if (root !== root.top || location.origin !== 'https://vidogrid.ai') return;
    if (root.__nexoraSiteAssistant) return;
    const integration = factory(root);
    let opened = false;
    const supported = () => /^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname);
    const controller = {
      visible: () => opened && !!root.__nexoraVidoAssistant,
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
    constructor() { this.active = false; this.tries = 0; this.accepted = 0; this.lastAccepted = null; this.readyAccepted = null;
      this.phase = 'off'; this.due = null; this.inFlight = false; this.reason = ''; }
    stop(reason, phase = 'paused') {
      this.active = false; this.due = null; this.phase = phase; this.reason = reason;
    }
    start(snapshot, now) {
      this.active = true; this.tries = 0; this.accepted = 0; this.lastAccepted = null; this.readyAccepted = null;
      this.inFlight = false; this.due = null; this.reason = ''; this.initialAllowed = true;
      if (snapshot.challenge || snapshot.processing) this.markAttempt(snapshot, now);
      return this.observe(snapshot, now);
    }
    markAttempt(snapshot, now) {
      this.inFlight = true; this.started = now; this.priorError = snapshot.errorToken;
      this.errorCleared = !snapshot.errorToken; this.due = null; this.readyAccepted = null;
    }
    observe(snapshot, now) {
      if (!this.active) return this.phase;
      if (!snapshot.route) { this.stop('Você saiu da página de geração.'); return this.phase; }
      if (snapshot.hidden) { this.stop('Página em segundo plano. Ative novamente ao voltar.'); return this.phase; }
      if (snapshot.success && !snapshot.accepted) { this.stop('O site exibiu um vídeo no resultado.', 'complete'); return this.phase; }
      if (this.inFlight && !snapshot.errorToken && !snapshot.challenge && snapshot.processing) this.errorCleared = true;
      if (snapshot.challenge) { this.phase = 'checking'; this.due = null; return this.phase; }
      if (snapshot.accepted && snapshot.acceptedToken && snapshot.acceptedToken !== this.lastAccepted) {
        this.lastAccepted = snapshot.acceptedToken; this.readyAccepted = snapshot.acceptedToken;
        this.accepted++; this.inFlight = false;
      }
      if (snapshot.processing && !snapshot.accepted) { this.phase = 'processing'; this.due = null; return this.phase; }
      if (this.inFlight) {
        const newFailure = snapshot.errorToken && (this.errorCleared || snapshot.errorToken !== this.priorError);
        if (!newFailure) { this.phase = 'waiting'; return this.phase; }
        this.inFlight = false;
      }
      if (snapshot.otherError) { this.stop('O site exibiu uma falha diferente de 503.'); return this.phase; }
      if (!snapshot.failure503 && !(snapshot.accepted && snapshot.acceptedToken === this.readyAccepted)
        && !(this.initialAllowed && snapshot.initialReady)) {
        this.phase = 'waiting'; this.due = null; return this.phase;
      }
      if (!snapshot.free) { this.stop('Custo zero não confirmado. Use o botão original do site.'); return this.phase; }
      if (!snapshot.enabled) { this.phase = 'waiting'; this.due = null; return this.phase; }
      if (this.due === null) this.due = now;
      this.phase = snapshot.failure503 ? 'countdown' : 'accepted'; return this.phase;
    }
    takeAttempt(snapshot, now) {
      this.observe(snapshot, now);
      if (!this.active || !['countdown','accepted'].includes(this.phase) || this.due === null || now < this.due) return false;
      this.tries++; this.initialAllowed = false; this.markAttempt(snapshot, now); this.phase = 'waiting'; return true;
    }
  }

  function install() {
    if (root !== root.top || location.origin !== 'https://vidogrid.ai' || !/^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname)) return {installed:false};
    if (root.__nexoraVidoAssistant) return root.__nexoraVidoAssistant.status();
    const document = root.document;
    const machine = new RetryState();
    const errorIds = new WeakMap(); let sequence = 0, dispatching = false, signature = '', resultEpoch = 0, previousCreated = '';
    const host = document.createElement('div');
    host.id = 'nexora-vidogrid-assistant';
    host.style.cssText = 'position:fixed!important;left:12px!important;right:12px!important;bottom:12px!important;z-index:2147483646!important;pointer-events:auto!important;';
    const shadow = host.attachShadow({mode:'closed'});
    shadow.innerHTML = `<style>
      :host{all:initial}*{box-sizing:border-box}section{font:13px/1.4 system-ui,sans-serif;color:#eef9fb;background:#0a1b23f5;border:1px solid #26505c;border-radius:16px;padding:13px 14px;box-shadow:0 8px 30px #0009}
      header{display:flex;align-items:center;gap:7px;color:#25eee2;font-size:10px;letter-spacing:1px;font-weight:750}header span{flex:1}b{display:block;margin-top:5px;font-size:15px;letter-spacing:0}p{margin:4px 0 9px;color:#b3cbd3;font-size:12px}nav{display:flex;gap:8px;align-items:center}button{font:600 12px system-ui;min-height:36px;border-radius:10px;border:1px solid #31505a;padding:7px 11px;background:#112c36;color:#eaf9fc;cursor:pointer}button.primary{background:#25eee2;color:#032126;border-color:#25eee2;flex:1}button.small{min-height:26px;padding:2px 8px;background:transparent}i{width:6px;height:6px;border-radius:100%;background:#25eee2}section[data-phase="checking"] i,section[data-phase="countdown"] i{background:#eac35c}#preload{font-size:11px;color:#74d8ce}section[data-collapsed="true"] p{display:none}
    </style><section data-phase="off"><header><i></i><span>NEXORA · VIDOGRID</span><button class="small" id="collapse" aria-label="Recolher painel">−</button><button class="small" id="close" aria-label="Fechar painel e parar repetição">×</button></header><b id="title"></b><p id="detail"></p><p id="preload"></p><nav><button class="primary" id="toggle"></button><button id="result">Ver resultado</button></nav></section>`;
    const $ = selector => shadow.querySelector(selector);
    let previousView = '';
    const clock = () => root.performance.now();
    const visible = element => !!(element?.isConnected && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden'
      && !element.closest('[aria-hidden="true"], [hidden]'));
    const text = element => (element?.innerText || element?.textContent || '').trim();
    function snapshot() {
      const buttons = [...document.querySelectorAll('button')].filter(item => !host.contains(item));
      const button = buttons.find(item => /^generate with grok\b/i.test(text(item)) && visible(item));
      const label = [...document.querySelectorAll('span')].find(item => text(item).toLowerCase() === 'output');
      const output = label?.parentElement?.parentElement;
      const error = output && [...output.querySelectorAll('p')].find(item => /^generation failed$/i.test(text(item)) && visible(item));
      if (error && !errorIds.has(error)) errorIds.set(error, ++sequence);
      const errorText = error ? text(error.parentElement) : '';
      const challenge = [...document.querySelectorAll('[role="dialog"], [data-state="open"]')]
        .some(item => visible(item) && /verify you.re human|complete the verification|security check/i.test(text(item)))
        || [...document.querySelectorAll('iframe')].some(item => !item.closest('[data-nexora-precheck]')
          && visible(item) && /^https:\/\/challenges\.cloudflare\.com\//i.test(item.getAttribute('src') || ''));
      const enabled = !!button && !button.disabled && button.getAttribute('aria-disabled') !== 'true';
      const processing = !!button && (!enabled || /generating|submitting|processing|queued/i.test(text(output))) && !error;
      const success = !!output && !error && [...output.querySelectorAll('video')].some(item => visible(item) && !!(item.currentSrc || item.getAttribute('src') || item.querySelector('source[src]')));
      const created = output && [...output.querySelectorAll('dt')].find(item => /^created at$/i.test(text(item)));
      const stamp = text(created?.nextElementSibling);
      if (previousCreated && !stamp) resultEpoch++;
      previousCreated = stamp;
      const acceptedToken = stamp ? stamp + '|' + resultEpoch : '';
      const accepted = !!output && !error && !!stamp && visible(output)
        && (/\bqueued\b|\brendering\b|\bcreating video\b/i.test(text(output)) || success);
      const free = !!button && /this generation will cost:\s*0\s*credits\b/i.test(text(button.parentElement));
      const initialReady = enabled && !error && !challenge && !processing && !accepted
        && !!document.querySelector('textarea')?.value.trim();
      return {route: location.origin === 'https://vidogrid.ai' && /^\/app\/video-gen(?:\/grok)?\/?$/.test(location.pathname),
        hidden: document.hidden, button, output, challenge, processing, success, enabled, free, accepted, acceptedToken,
        initialReady,
        errorToken: error ? errorIds.get(error) : null, failure503: !!error && /\bstatus\s*503\b/i.test(errorText),
        otherError: !!error && !/\bstatus\s*503\b/i.test(errorText)};
    }
    function formSignature() {
      const prompt = document.querySelector('textarea[placeholder*="Grok"]') || document.querySelector('textarea');
      const form = prompt?.closest('aside') || document;
      const fields = [...form.querySelectorAll('textarea,select,input:not([type="hidden"])')]
        .filter(item => !/turnstile|captcha/i.test(item.name || ''))
        .map(item => [item.type,item.value,item.checked]);
      const choices = [...form.querySelectorAll('[role="tab"][aria-selected="true"],button[data-state="on"],button[aria-pressed="true"]')].map(item => text(item));
      return JSON.stringify([location.pathname, fields, choices]);
    }
    function render(now) {
      const elapsed = machine.inFlight ? Math.floor((now-machine.started)/1000) : 0;
      const labels = {off:'Repetir após falha 503',checking:'Aguardando a verificação',processing:'Solicitação em andamento',waiting:'Aguardando resposta do site',
        countdown:'Servidor ocupado · 503',accepted:'Solicitação aceita · enviando outra',paused:'Repetição pausada',complete:'Vídeo disponível'};
      const title = labels[machine.phase] || labels.waiting;
      const totals = `${machine.tries} enviadas · ${machine.accepted} aceitas`;
      const detail = machine.phase === 'off' ? 'Repete após 503 ou novo aceite · sem intervalo ou limite de ciclos.'
        : machine.phase === 'countdown' ? `Nova tentativa ao receber a falha · ${machine.tries} realizadas`
        : machine.phase === 'checking' ? `${elapsed}s na checagem · ${totals}`
        : machine.inFlight && machine.active ? `${elapsed}s de espera · ${totals}`
        : machine.active ? totals
        : machine.reason;
      const prepared = root.__nexoraVidoPreload?.status();
      const preparation = prepared?.approvalReady ? 'Próxima checagem pronta'
        : prepared?.assigned ? 'Checagem antecipada em uso'
        : prepared?.needsInteraction ? 'Próxima checagem aguardará sua interação'
        : prepared?.preparing ? 'Preparando a próxima checagem'
        : machine.active ? 'Antecipação da próxima checagem ligada' : 'A antecipação acompanha a repetição';
      const view = JSON.stringify([title,detail,machine.phase,machine.active,preparation]);
      if (view === previousView) return; previousView = view;
      $('#title').textContent = title; $('#detail').textContent = detail;
      $('#preload').textContent = preparation;
      $('section').dataset.phase = machine.phase;
      $('#toggle').textContent = machine.active ? 'Pausar repetição' : 'Ativar repetição';
    }
    function tick() {
      if (!host.isConnected) { destroy(); return; }
      const now = clock(), state = snapshot();
      if (!state.route) { root.__nexoraSiteAssistant?.hide(); return; }
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
      else { signature = formSignature(); machine.start(snapshot(), clock()); }
      tick();
    }
    function pause() { machine.stop('Pausado. Toque em Ativar repetição para continuar.'); root.__nexoraVidoPreload?.sync(); render(clock()); }
    function onClick(event) {
      if (dispatching || !machine.active || event.composedPath().includes(host)) return;
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
      root.clearInterval(timer); machine.stop('Assistente removido.');
      root.__nexoraVidoPreload?.restore();
      observer.disconnect();
      document.removeEventListener('click', onClick, true);
      document.removeEventListener('input', onChange, true);
      document.removeEventListener('change', onChange, true);
      document.removeEventListener('visibilitychange', tick);
      host.remove();
      if (root.__nexoraVidoAssistant === api) delete root.__nexoraVidoAssistant;
    }
    $('#toggle').addEventListener('click', toggle);
    $('#close').addEventListener('click', () => root.__nexoraSiteAssistant?.hide());
    $('#collapse').addEventListener('click', () => { const section = $('section');
      const collapsed = section.dataset.collapsed !== 'true'; section.dataset.collapsed = String(collapsed);
      $('#collapse').textContent = collapsed ? '+' : '−'; });
    $('#result').addEventListener('click', () => snapshot().output?.scrollIntoView({behavior:'smooth',block:'start'}));
    document.addEventListener('click', onClick, true);
    document.addEventListener('input', onChange, true);
    document.addEventListener('change', onChange, true);
    document.addEventListener('visibilitychange', tick);
    document.documentElement.appendChild(host);
    const timer = root.setInterval(tick, 500);
    const api = {destroy, toggle, pause, status: () => ({active:machine.active,phase:machine.phase,tries:machine.tries,accepted:machine.accepted,
      remaining:machine.due===null?null:Math.max(0,Math.ceil((machine.due-clock())/1000)),inFlight:machine.inFlight,reason:machine.reason,
      snapshot:(()=>{const state=snapshot();return {failure503:state.failure503,challenge:state.challenge,enabled:state.enabled,free:state.free,success:state.success,accepted:state.accepted};})()})};
    root.__nexoraVidoAssistant = api;
    const observer = new MutationObserver(tick);
    observer.observe(document.body, {subtree:true,childList:true,characterData:true,attributes:true,
      attributeFilter:['disabled','aria-disabled','hidden','aria-hidden','data-state']});
    tick();
    return {installed:true,automaticRetries:'off',scope:'current profile main page only'};
  }
  return {RetryState, install};
});
