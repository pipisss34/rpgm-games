/*
 * ime.js - kompatibilitas input untuk WebView:
 * 1) kolom input/textarea HTML -> dialog teks native Android (keyboard pasti muncul)
 * 2) PIXI: ketukan jari ('tap') juga memicu 'click' (plugin yang hanya dengar 'click')
 */
(function (w) {
  'use strict';
  if (w.__imeHelper) return;
  w.__imeHelper = true;
  var B = w.AndroidIME;

  // ---- 2) tap -> click (aman dari dobel: abaikan click kedua pada objek yang sama < 120 ms)
  function patchPixi() {
    var P = w.PIXI;
    var IM = P && P.interaction && P.interaction.InteractionManager;
    if (!IM) return false;
    var proto = IM.prototype;
    if (proto.__clickPatched || typeof proto.dispatchEvent !== 'function') return true;
    var d = proto.dispatchEvent;
    proto.dispatchEvent = function (o, e, data) {
      var now = Date.now();
      if (e === 'click') {
        if (o.__ck && now - o.__ck < 120) { o.__ck = 0; return; }
        o.__ck = now;
        return d.call(this, o, e, data);
      }
      d.call(this, o, e, data);
      if (e === 'tap' && data && !data.stopped && !(o.__ck && now - o.__ck < 120)) {
        o.__ck = now;
        d.call(this, o, 'click', data);
      }
    };
    proto.__clickPatched = true;
    return true;
  }
  var tries = 0;
  (function poll() { if (!patchPixi() && tries++ < 400) setTimeout(poll, 50); })();

  // ---- 1) input HTML -> dialog native
  if (!B) return;
  function editable(t) {
    if (!t || !t.tagName) return false;
    var n = t.tagName;
    if (n === 'TEXTAREA' || t.isContentEditable) return true;
    return n === 'INPUT' && !/^(button|checkbox|radio|range|submit|image|file|color|reset)$/i.test(t.type || 'text');
  }
  var busy = false, cur = null;
  w.__imeResult = function (text) {
    var el = cur;
    cur = null;
    setTimeout(function () { busy = false; }, 300);
    if (!el) return;
    if (text !== null && text !== undefined) {
      try {
        el.value = text;
        el.dispatchEvent(new Event('input', { bubbles: true }));
        el.dispatchEvent(new Event('change', { bubbles: true }));
      } catch (e) { /* abaikan */ }
    }
    try { el.blur(); } catch (e2) { /* abaikan */ }
  };
  function open(el) {
    if (busy) return;
    busy = true;
    cur = el;
    var hint = el.placeholder || el.getAttribute('aria-label') || 'Masukkan teks';
    try {
      B.edit(String(el.value || ''), el.maxLength > 0 ? el.maxLength : 0, String(hint));
    } catch (e) { busy = false; cur = null; }
  }
  document.addEventListener('focusin', function (e) { if (editable(e.target)) open(e.target); }, true);
})(window);
