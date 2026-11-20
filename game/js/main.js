// Sample uji APK WebView. BUKAN game RPG Maker sungguhan: ini hanya memeriksa
// bahwa input tombol virtual, asset loader, localStorage dan audio berfungsi.
// Ganti seluruh isi folder game/ dengan game MV/MZ kamu.
(function () {
  'use strict';

  var cv = document.getElementById('c');
  var g = cv.getContext('2d');
  var W = 0, H = 0;

  function resize() {
    var dpr = window.devicePixelRatio || 1;
    W = window.innerWidth;
    H = window.innerHeight;
    cv.width = W * dpr;
    cv.height = H * dpr;
    cv.style.width = W + 'px';
    cv.style.height = H + 'px';
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
  }
  window.addEventListener('resize', resize);
  resize();

  // ---- pemetaan tombol sama seperti Input.keyMapper RPG Maker MV/MZ ----
  var MAP = {
    9: 'tab', 13: 'ok', 16: 'shift', 17: 'control', 18: 'control', 27: 'escape', 32: 'ok',
    33: 'pageup', 34: 'pagedown', 37: 'left', 38: 'up', 39: 'right', 40: 'down',
    81: 'pageup', 87: 'pagedown', 88: 'escape', 90: 'ok', 113: 'F2', 114: 'F3', 120: 'debug'
  };
  var LAMPS = ['up', 'down', 'left', 'right', 'ok', 'escape', 'shift', 'control',
               'tab', 'pageup', 'pagedown', 'F2', 'F3', 'debug'];
  var down = {};
  var lastKey = '-';
  var okCount = 0;
  var hue = 200;

  function onKey(e, v) {
    var n = MAP[e.keyCode];
    lastKey = e.keyCode + (n ? ' (' + n + ')' : ' (tidak dipetakan)');
    if (!n) return;
    e.preventDefault();
    if (v && !down[n]) pressed(n);
    down[n] = v;
  }
  document.addEventListener('keydown', function (e) { onKey(e, true); });
  document.addEventListener('keyup', function (e) { onKey(e, false); });

  function pressed(n) {
    if (n === 'ok') { okCount++; beep(); }
    if (n === 'escape') { px = 0.5; py = 0.5; }
    if (n === 'pageup') hue = (hue + 30) % 360;
    if (n === 'pagedown') hue = (hue + 330) % 360;
  }

  // ---- uji lingkungan & asset ----
  var tests = [];
  function addTest(name, ok, note) { tests.push({ name: name, ok: ok, note: note || '' }); }

  addTest('Mode browser (tanpa require/process, jadi Utils.isNwjs() = false)',
          typeof require === 'undefined' && typeof process === 'undefined',
          'require=' + typeof require + ', process=' + typeof process);

  try {
    var n = (parseInt(localStorage.getItem('sample.launches') || '0', 10) || 0) + 1;
    localStorage.setItem('sample.launches', String(n));
    addTest('localStorage (tempat save game)', true, 'game dibuka ' + n + 'x');
  } catch (err) {
    addTest('localStorage (tempat save game)', false, String(err));
  }

  var xhr = new XMLHttpRequest();
  xhr.open('GET', 'data/test.json');
  xhr.onload = function () {
    try { addTest('XHR data/test.json', xhr.status < 400, JSON.parse(xhr.responseText).message); }
    catch (err) { addTest('XHR data/test.json', false, String(err)); }
  };
  xhr.onerror = function () { addTest('XHR data/test.json', false, 'gagal'); };
  xhr.send();

  function imgTest(label, src) {
    var im = new Image();
    im.onload = function () { addTest(label, true, im.width + 'x' + im.height); };
    im.onerror = function () { addTest(label, false, 'gagal dimuat'); };
    im.src = src;
  }
  imgTest('Gambar img/test.png', 'img/test.png');
  imgTest('Huruf besar/kecil beda (img/TEST.PNG)', 'img/TEST.PNG');

  // ---- audio (WebAudio, seperti MV/MZ) ----
  var ac = null, beepBuf = null;
  try { ac = new (window.AudioContext || window.webkitAudioContext)(); } catch (err) { ac = null; }
  if (ac) {
    var ax = new XMLHttpRequest();
    ax.open('GET', 'audio/beep.wav');
    ax.responseType = 'arraybuffer';
    ax.onload = function () {
      ac.decodeAudioData(ax.response, function (buf) {
        beepBuf = buf;
        addTest('Audio WebAudio (beep.wav)', true, buf.duration.toFixed(2) + ' dtk');
      }, function () { addTest('Audio WebAudio (beep.wav)', false, 'decode gagal'); });
    };
    ax.onerror = function () { addTest('Audio WebAudio (beep.wav)', false, 'gagal dimuat'); };
    ax.send();
  } else {
    addTest('Audio WebAudio', false, 'AudioContext tidak ada');
  }
  function beep() {
    if (!ac || !beepBuf) return;
    if (ac.state === 'suspended') ac.resume();
    var s = ac.createBufferSource();
    s.buffer = beepBuf;
    s.connect(ac.destination);
    s.start(0);
  }

  // ---- gambar ----
  var px = 0.5, py = 0.5;
  var last = performance.now();

  function frame(now) {
    var dt = Math.min(0.05, (now - last) / 1000);
    last = now;
    var sp = (down.shift ? 0.9 : 0.45) * dt;
    if (down.left) px -= sp;
    if (down.right) px += sp;
    if (down.up) py -= sp;
    if (down.down) py += sp;
    px = Math.max(0, Math.min(1, px));
    py = Math.max(0, Math.min(1, py));
    draw();
    requestAnimationFrame(frame);
  }

  function draw() {
    g.fillStyle = '#10142a';
    g.fillRect(0, 0, W, H);

    var fs = Math.max(11, Math.round(H * 0.034));
    g.font = fs + 'px monospace';
    g.textBaseline = 'top';
    var x0 = Math.round(W * 0.12), y = Math.round(H * 0.07);

    g.fillStyle = '#ffd866';
    g.fillText('SAMPLE GAME - uji APK WebView', x0, y);
    y += fs * 1.6;
    g.fillStyle = '#9aa';
    g.fillText('Layar ' + W + 'x' + H + '  @' + (window.devicePixelRatio || 1) + 'x   tombol terakhir: ' + lastKey, x0, y);
    y += fs * 1.5;

    for (var i = 0; i < tests.length; i++) {
      var t = tests[i];
      g.fillStyle = t.ok ? '#7CFC9A' : '#ff6b6b';
      g.fillText((t.ok ? '[OK] ' : '[X]  ') + t.name + (t.note ? ' - ' + t.note : ''), x0, y);
      y += fs * 1.25;
    }

    // lampu tombol
    var ly = Math.round(H * 0.50);
    var lw = Math.min(W * 0.76 / LAMPS.length, fs * 6);
    var lx0 = (W - lw * LAMPS.length) / 2;
    g.textAlign = 'center';
    g.font = Math.round(fs * 0.62) + 'px monospace';
    for (var k = 0; k < LAMPS.length; k++) {
      var on = !!down[LAMPS[k]];
      g.fillStyle = on ? '#ffd866' : '#26304f';
      g.fillRect(lx0 + k * lw + 2, ly, lw - 4, fs * 1.3);
      g.fillStyle = on ? '#10142a' : '#8895c0';
      g.fillText(LAMPS[k], lx0 + k * lw + lw / 2, ly + fs * 0.32);
    }

    // arena pemain (tengah-bawah supaya tidak tertutup tombol)
    var ax0 = W * 0.30, ay0 = H * 0.62, aw = W * 0.40, ah = H * 0.32;
    g.strokeStyle = '#3b4a80';
    g.lineWidth = 2;
    g.strokeRect(ax0, ay0, aw, ah);
    var sz = Math.min(aw, ah) * 0.18;
    g.fillStyle = 'hsl(' + hue + ',80%,60%)';
    g.fillRect(ax0 + px * (aw - sz), ay0 + py * (ah - sz), sz, sz);
    g.textAlign = 'left';
    g.fillStyle = '#9aa';
    g.font = Math.round(fs * 0.7) + 'px monospace';
    g.fillText('OK=' + okCount + '  panah=gerak  DASH=cepat  PGUP/PGDN=warna  MENU=reset', ax0, ay0 + ah + 4);
  }

  requestAnimationFrame(frame);
})();
