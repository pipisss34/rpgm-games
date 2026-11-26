/* ime.js - munculkan keyboard Android saat kolom input HTML (input/textarea) fokus. */
(function (w) {
  'use strict';
  var B = w.AndroidIME;
  if (!B || w.__imeHelper) return;
  w.__imeHelper = true;
  function ed(t) {
    if (!t || !t.tagName) return false;
    var n = t.tagName;
    if (n === 'TEXTAREA' || t.isContentEditable) return true;
    return n === 'INPUT' && !/^(button|checkbox|radio|range|submit|image|file|color|reset)$/i.test(t.type || 'text');
  }
  function show() { try { B.show(); } catch (e) {} }
  function hide() { try { B.hide(); } catch (e) {} }
  document.addEventListener('focusin', function (e) { if (ed(e.target)) show(); }, true);
  document.addEventListener('click', function (e) { if (ed(e.target)) { try { e.target.focus(); } catch (x) {} show(); } }, true);
  document.addEventListener('touchend', function (e) { if (ed(e.target)) show(); }, true);
  document.addEventListener('focusout', function () {
    setTimeout(function () { if (!ed(document.activeElement)) hide(); }, 80);
  }, true);
})(window);
