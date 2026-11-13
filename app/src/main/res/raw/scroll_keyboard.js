(function () {
  'use strict';
  if (!/^https?:$/.test(location.protocol) || !window.__nexoraScrollKeyboard) return;
  const bridge = window.__nexoraScrollKeyboard;
  let gesture = null;

  function insideEditor(event) {
    // composedPath also sees inputs inside open shadow roots and custom editors.
    return event.composedPath().some(element => {
      if (!(element instanceof Element)) return false;
      if (element instanceof HTMLTextAreaElement || element.isContentEditable) return true;
      if (element instanceof HTMLInputElement)
        return /^(text|search|email|password|number|tel|url)$/.test(element.type);
      return element.getAttribute('role') === 'textbox';
    });
  }

  function dismiss() {
    let focused = document.activeElement;
    while (focused && focused.shadowRoot && focused.shadowRoot.activeElement)
      focused = focused.shadowRoot.activeElement;
    if (focused && typeof focused.blur === 'function') focused.blur();
    bridge.postMessage('hide');
  }

  document.addEventListener('touchstart', event => {
    gesture = event.touches.length === 1 ? {
      x: event.touches[0].clientX, y: event.touches[0].clientY,
      inside: insideEditor(event), dismissed: false
    } : null;
  }, { capture: true, passive: true });

  document.addEventListener('touchmove', event => {
    if (event.touches.length !== 1) { gesture = null; return; }
    if (!gesture || gesture.inside || gesture.dismissed) return;
    const dx = event.touches[0].clientX - gesture.x;
    const dy = event.touches[0].clientY - gesture.y;
    if (dx * dx + dy * dy < 144) return;
    gesture.dismissed = true;
    dismiss();
  }, { capture: true, passive: true });

  document.addEventListener('touchend', () => { gesture = null; }, { capture: true, passive: true });
  document.addEventListener('touchcancel', () => { gesture = null; }, { capture: true, passive: true });
})();
