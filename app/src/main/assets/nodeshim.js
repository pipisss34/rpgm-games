/*
 * nodeshim.js - emulasi ringan Node.js / NW.js untuk WebView Android.
 * Menyediakan require(), process, Buffer, nw, dan modul fs/path/os/util/events/url/child_process
 * (fs memakai jembatan native AndroidFS: baca dari aset game, tulis ke penyimpanan app).
 *
 * PENTING: typeof process sengaja 'function' (bukan 'object'). Utils.isNwjs() milik RPG Maker
 * mensyaratkan typeof process === 'object', jadi inti MV/MZ tetap di mode browser (aman),
 * sementara plugin yang memanggil require('fs') / process.platform tidak lagi crash.
 */
(function (global) {
  'use strict';
  var B = global.AndroidFS;
  if (!B || global.__nodeShim) return;
  global.__nodeShim = true;

  function warn(msg) {
    try { console.error('[nodeshim] ' + msg); } catch (e) { /* abaikan */ }
  }

  // ------------------------------------------------------------ util dasar
  function noopProxy() {
    var f = function () {};
    var p = new Proxy(f, {
      get: function (t, k) {
        if (typeof k === 'symbol' || k === 'then') return undefined;
        return p;
      },
      apply: function () { return p; },
      construct: function () { return p; }
    });
    return p;
  }

  function withFallback(obj, name) {
    return new Proxy(obj, {
      get: function (t, k) {
        if (k in t) return t[k];
        if (typeof k === 'symbol' || k === 'then') return undefined;
        warn(name + '.' + String(k) + ' tidak didukung (stub kosong)');
        return noopProxy();
      }
    });
  }

  var ERRNO = { ENOENT: -2, EEXIST: -17, ENOTDIR: -20, EISDIR: -21, EACCES: -13, ENOTEMPTY: -39 };
  var ERRMSG = {
    ENOENT: 'no such file or directory', EEXIST: 'file already exists', ENOTDIR: 'not a directory',
    EISDIR: 'illegal operation on a directory', EACCES: 'permission denied', ENOTEMPTY: 'directory not empty'
  };
  function fsErr(code, syscall, p) {
    var e = new Error(code + ': ' + (ERRMSG[code] || code) + ", " + syscall + " '" + p + "'");
    e.code = code;
    e.errno = ERRNO[code] || -1;
    e.syscall = syscall;
    e.path = p;
    return e;
  }

  // ------------------------------------------------------------ path (posix)
  function normalizeArray(parts, allowAbove) {
    var res = [];
    for (var i = 0; i < parts.length; i++) {
      var p = parts[i];
      if (!p || p === '.') continue;
      if (p === '..') {
        if (res.length && res[res.length - 1] !== '..') res.pop();
        else if (allowAbove) res.push('..');
      } else res.push(p);
    }
    return res;
  }

  var path = {
    sep: '/',
    delimiter: ':',
    isAbsolute: function (p) { return String(p).charAt(0) === '/'; },
    normalize: function (p) {
      p = String(p);
      if (!p) return '.';
      var abs = p.charAt(0) === '/';
      var trail = p.charAt(p.length - 1) === '/';
      var r = normalizeArray(p.split('/'), !abs).join('/');
      if (!r && !abs) r = '.';
      if (r && trail) r += '/';
      return (abs ? '/' : '') + r;
    },
    join: function () {
      var a = [];
      for (var i = 0; i < arguments.length; i++) if (arguments[i]) a.push(String(arguments[i]));
      return a.length ? path.normalize(a.join('/')) : '.';
    },
    resolve: function () {
      var res = '', abs = false;
      for (var i = arguments.length - 1; i >= -1 && !abs; i--) {
        var p = i >= 0 ? String(arguments[i]) : '/';
        if (!p) continue;
        res = p + '/' + res;
        abs = p.charAt(0) === '/';
      }
      res = normalizeArray(res.split('/'), !abs).join('/');
      return ((abs ? '/' : '') + res) || '.';
    },
    dirname: function (p) {
      var orig = String(p);
      p = orig.replace(/\/+$/, '');
      if (!p) return orig.charAt(0) === '/' ? '/' : '.';
      var i = p.lastIndexOf('/');
      if (i < 0) return '.';
      if (i === 0) return '/';
      return p.slice(0, i).replace(/\/+$/, '') || '/';
    },
    basename: function (p, ext) {
      p = String(p).replace(/\/+$/, '');
      var b = p.slice(p.lastIndexOf('/') + 1);
      if (ext && b.length > ext.length && b.slice(-ext.length) === ext) b = b.slice(0, -ext.length);
      return b;
    },
    extname: function (p) {
      var b = path.basename(p);
      var i = b.lastIndexOf('.');
      return i <= 0 ? '' : b.slice(i);
    },
    relative: function (from, to) {
      var f = path.resolve(from).split('/').filter(Boolean);
      var t = path.resolve(to).split('/').filter(Boolean);
      var i = 0;
      while (i < f.length && i < t.length && f[i] === t[i]) i++;
      var out = [];
      for (var j = i; j < f.length; j++) out.push('..');
      return out.concat(t.slice(i)).join('/');
    },
    parse: function (p) {
      p = String(p);
      var base = path.basename(p), ext = path.extname(p);
      var dir = path.dirname(p);
      return { root: p.charAt(0) === '/' ? '/' : '', dir: dir === '.' && p.indexOf('/') < 0 ? '' : dir,
        base: base, ext: ext, name: ext ? base.slice(0, -ext.length) : base };
    },
    format: function (o) {
      var dir = o.dir || o.root || '';
      var base = o.base || ((o.name || '') + (o.ext || ''));
      return dir ? (dir === o.root ? dir + base : dir + '/' + base) : base;
    }
  };
  path.posix = path;
  path.win32 = path;

  // ------------------------------------------------------------ Buffer
  function bytesToB64(u8) {
    var s = '';
    for (var i = 0; i < u8.length; i += 0x8000) {
      s += String.fromCharCode.apply(null, u8.subarray(i, i + 0x8000));
    }
    return btoa(s);
  }
  function b64ToBytes(b64) {
    var s = atob(b64);
    var u8 = new Uint8Array(s.length);
    for (var i = 0; i < s.length; i++) u8[i] = s.charCodeAt(i);
    return u8;
  }

  if (typeof global.Buffer === 'undefined') {
    var Buf = class Buffer extends Uint8Array {
      static from(v, enc) {
        var b, u;
        if (typeof v === 'string') {
          enc = (enc || 'utf8').toLowerCase();
          if (enc === 'base64') u = b64ToBytes(v);
          else if (enc === 'hex') {
            u = new Uint8Array(v.length >> 1);
            for (var i = 0; i < u.length; i++) u[i] = parseInt(v.substr(i * 2, 2), 16);
          } else if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') {
            u = new Uint8Array(v.length);
            for (var k = 0; k < v.length; k++) u[k] = v.charCodeAt(k) & 255;
          } else u = new TextEncoder().encode(v);
        } else if (v instanceof ArrayBuffer) {
          u = new Uint8Array(v);
        } else {
          u = v || [];
        }
        b = new Buffer(u.length);
        b.set(u);
        return b;
      }
      static alloc(n, fill) {
        var b = new Buffer(n);
        if (fill !== undefined) b.fill(typeof fill === 'string' ? fill.charCodeAt(0) : fill);
        return b;
      }
      static allocUnsafe(n) { return new Buffer(n); }
      static isBuffer(x) { return x instanceof Buffer; }
      static byteLength(s) { return typeof s === 'string' ? new TextEncoder().encode(s).length : s.length; }
      static concat(list, total) {
        if (total === undefined) { total = 0; list.forEach(function (x) { total += x.length; }); }
        var b = new Buffer(total), off = 0;
        list.forEach(function (x) { if (off < total) { b.set(x.subarray(0, total - off), off); off += x.length; } });
        return b;
      }
      toString(enc, start, end) {
        var u = this.subarray(start || 0, end === undefined ? this.length : end);
        enc = (enc || 'utf8').toLowerCase();
        if (enc === 'base64') return bytesToB64(u);
        if (enc === 'hex') {
          var h = '';
          for (var i = 0; i < u.length; i++) h += (u[i] < 16 ? '0' : '') + u[i].toString(16);
          return h;
        }
        if (enc === 'latin1' || enc === 'binary' || enc === 'ascii') {
          var s = '';
          for (var j = 0; j < u.length; j++) s += String.fromCharCode(u[j]);
          return s;
        }
        return new TextDecoder('utf-8').decode(u);
      }
      equals(o) {
        if (!o || o.length !== this.length) return false;
        for (var i = 0; i < this.length; i++) if (this[i] !== o[i]) return false;
        return true;
      }
      slice(s, e) { return this.subarray(s, e); }
      toJSON() { return { type: 'Buffer', data: Array.prototype.slice.call(this) }; }
    };
    global.Buffer = Buf;
  }
  var Buffer = global.Buffer;

  // ------------------------------------------------------------ fs (jembatan native)
  function P(p) {
    if (p && typeof p === 'object' && p.pathname) p = p.pathname;
    p = String(p);
    if (p.indexOf('file://') === 0) p = p.slice(7);
    p = p.replace(/\\/g, '/');
    return path.resolve('/', p);
  }
  function statRaw(vp) {
    var s = B.stat(vp);
    return s ? JSON.parse(s) : null;
  }
  function encOf(o) { return typeof o === 'string' ? o : (o && o.encoding) || null; }
  function isUtf8(e) { return /^utf-?8$/i.test(e); }

  function Stats(r) {
    this.size = r.s;
    this.mode = r.t === 'd' ? 16877 : 33188;
    this.mtimeMs = this.ctimeMs = this.atimeMs = this.birthtimeMs = r.m || 0;
    this.mtime = this.ctime = this.atime = this.birthtime = new Date(r.m || 0);
    this._d = r.t === 'd';
  }
  Stats.prototype.isFile = function () { return !this._d; };
  Stats.prototype.isDirectory = function () { return this._d; };
  Stats.prototype.isSymbolicLink = function () { return false; };

  function missing(vp, syscall, orig) {
    var s = statRaw(vp);
    return fsErr(s && s.t === 'd' ? 'EISDIR' : 'ENOENT', syscall, orig);
  }

  var fs = {
    constants: { F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1 },
    F_OK: 0, R_OK: 4, W_OK: 2, X_OK: 1,
    existsSync: function (p) { try { return !!B.stat(P(p)); } catch (e) { return false; } },
    readFileSync: function (p, opts) {
      var vp = P(p), enc = encOf(opts);
      if (enc && isUtf8(enc)) {
        var t = B.readText(vp);
        if (t === null || t === undefined) throw missing(vp, 'open', p);
        return t;
      }
      var b64 = B.readB64(vp);
      if (b64 === null || b64 === undefined) throw missing(vp, 'open', p);
      var buf = Buffer.from(b64ToBytes(b64));
      return enc ? buf.toString(enc) : buf;
    },
    statSync: function (p) {
      var s = statRaw(P(p));
      if (!s) throw fsErr('ENOENT', 'stat', p);
      return new Stats(s);
    },
    readdirSync: function (p, opts) {
      var vp = P(p), s = statRaw(vp);
      if (!s) throw fsErr('ENOENT', 'scandir', p);
      if (s.t !== 'd') throw fsErr('ENOTDIR', 'scandir', p);
      var names = JSON.parse(B.list(vp));
      if (opts && opts.withFileTypes) {
        return names.map(function (n) {
          var st = statRaw(path.join(vp, n));
          var d = !!st && st.t === 'd';
          return { name: n, isFile: function () { return !d; }, isDirectory: function () { return d; },
            isSymbolicLink: function () { return false; } };
        });
      }
      return names;
    },
    mkdirSync: function (p, opts) {
      var vp = P(p), s = statRaw(vp);
      if (s) {
        if (opts && opts.recursive && s.t === 'd') return undefined;
        throw fsErr('EEXIST', 'mkdir', p);
      }
      if (!B.mkdir(vp)) throw fsErr('EACCES', 'mkdir', p);
      return undefined;
    },
    unlinkSync: function (p) {
      var vp = P(p);
      if (!B.remove(vp)) throw fsErr(statRaw(vp) ? 'EACCES' : 'ENOENT', 'unlink', p);
    },
    rmdirSync: function (p) {
      var vp = P(p);
      if (!B.remove(vp)) throw fsErr(statRaw(vp) ? 'EACCES' : 'ENOENT', 'rmdir', p);
    },
    rmSync: function (p, opts) {
      var vp = P(p);
      if (!B.remove(vp) && !(opts && opts.force)) throw fsErr(statRaw(vp) ? 'EACCES' : 'ENOENT', 'rm', p);
    },
    renameSync: function (a, b) {
      if (!B.rename(P(a), P(b))) throw fsErr('ENOENT', 'rename', a);
    },
    copyFileSync: function (a, b) {
      var data = fs.readFileSync(a);
      fs.writeFileSync(b, data);
    },
    accessSync: function (p) {
      if (!B.stat(P(p))) throw fsErr('ENOENT', 'access', p);
    },
    chmodSync: function () {},
    utimesSync: function () {},
    watch: function () { return { close: function () {}, on: function () { return this; } }; },
    watchFile: function () {},
    unwatchFile: function () {}
  };
  fs.lstatSync = fs.statSync;

  function writeImpl(p, data, opts, append) {
    var vp = P(p);
    if (opts && opts.flag && String(opts.flag).charAt(0) === 'a') append = true;
    var ok;
    if (typeof data === 'string') {
      var enc = encOf(opts);
      if (enc && !isUtf8(enc)) ok = B.writeB64(vp, bytesToB64(Buffer.from(data, enc)), !!append);
      else ok = B.writeText(vp, data, !!append);
    } else {
      if (data === null || data === undefined) data = '';
      var u8 = data instanceof Uint8Array ? data
        : (data instanceof ArrayBuffer ? new Uint8Array(data) : Buffer.from(String(data)));
      ok = B.writeB64(vp, bytesToB64(u8), !!append);
    }
    if (!ok) throw fsErr('EACCES', 'open', p);
  }
  fs.writeFileSync = function (p, data, opts) { writeImpl(p, data, opts, false); };
  fs.appendFileSync = function (p, data, opts) { writeImpl(p, data, opts, true); };

  ['readFile', 'writeFile', 'appendFile', 'readdir', 'stat', 'lstat', 'mkdir', 'unlink', 'rmdir',
    'rm', 'rename', 'copyFile', 'access', 'chmod', 'utimes'].forEach(function (n) {
    var sync = fs[n + 'Sync'];
    fs[n] = function () {
      var args = Array.prototype.slice.call(arguments);
      var cb = typeof args[args.length - 1] === 'function' ? args.pop() : function () {};
      setTimeout(function () {
        var r, err = null;
        try { r = sync.apply(fs, args); } catch (x) { err = x; }
        cb(err, r);
      }, 0);
    };
  });
  fs.exists = function (p, cb) { setTimeout(function () { cb(fs.existsSync(p)); }, 0); };
  fs.promises = {};
  ['readFile', 'writeFile', 'appendFile', 'readdir', 'stat', 'lstat', 'mkdir', 'unlink', 'rmdir',
    'rm', 'rename', 'copyFile', 'access'].forEach(function (n) {
    fs.promises[n] = function () {
      var args = arguments;
      return new Promise(function (res, rej) {
        try { res(fs[n + 'Sync'].apply(fs, args)); } catch (e) { rej(e); }
      });
    };
  });

  // ------------------------------------------------------------ process
  var started = Date.now();
  var proc = function process() {};
  var nop = function () { return proc; };
  Object.assign(proc, {
    title: 'node', version: 'v18.17.0',
    versions: { node: '18.17.0', v8: '10.2.154', nw: '0.78.0', chromium: '114.0' },
    arch: 'arm64', platform: 'linux', release: { name: 'node' },
    argv: ['/nw', '/'], execArgv: [], execPath: '/nw',
    env: { HOME: '/', USERPROFILE: '/', APPDATA: '/', LOCALAPPDATA: '/', TEMP: '/tmp', TMP: '/tmp' },
    pid: 1, ppid: 0, exitCode: 0,
    cwd: function () { return '/'; },
    chdir: function () {},
    umask: function () { return 18; },
    uptime: function () { return (Date.now() - started) / 1000; },
    hrtime: function (prev) {
      var t = (global.performance ? performance.now() : Date.now());
      var s = Math.floor(t / 1000), n = Math.floor((t % 1000) * 1e6);
      if (prev) { s -= prev[0]; n -= prev[1]; if (n < 0) { s--; n += 1e9; } }
      return [s, n];
    },
    memoryUsage: function () {
      var m = (global.performance && performance.memory) || {};
      return { rss: m.totalJSHeapSize || 0, heapTotal: m.totalJSHeapSize || 0, heapUsed: m.usedJSHeapSize || 0, external: 0 };
    },
    nextTick: function (fn) {
      var a = Array.prototype.slice.call(arguments, 1);
      Promise.resolve().then(function () { fn.apply(null, a); });
    },
    exit: function () { B.quit(); },
    kill: function () {}, abort: function () { B.quit(); },
    emitWarning: function () {}, binding: function () { return {}; },
    on: nop, once: nop, off: nop, addListener: nop, removeListener: nop, removeAllListeners: nop,
    emit: function () { return false; }, listeners: function () { return []; },
    mainModule: { filename: '/index.html', id: '.', paths: [], exports: {} },
    stdout: { write: function () { return true; }, isTTY: false, columns: 80 },
    stderr: { write: function () { return true; }, isTTY: false },
    stdin: { isTTY: false, on: function () {}, setEncoding: function () {} }
  });
  global.process = proc;
  if (typeof global.global === 'undefined') global.global = global;
  if (typeof global.__dirname === 'undefined') global.__dirname = '/';
  if (typeof global.__filename === 'undefined') global.__filename = '/index.html';

  // ------------------------------------------------------------ modul kecil lain
  var os = {
    EOL: '\n', platform: function () { return 'linux'; }, type: function () { return 'Linux'; },
    arch: function () { return 'arm64'; }, release: function () { return '5.10'; },
    hostname: function () { return 'localhost'; }, homedir: function () { return '/'; },
    tmpdir: function () { return '/tmp'; }, endianness: function () { return 'LE'; },
    cpus: function () { return [{ model: 'cpu', speed: 0, times: {} }]; },
    totalmem: function () { return 4e9; }, freemem: function () { return 2e9; },
    uptime: function () { return process.uptime(); }, networkInterfaces: function () { return {}; },
    userInfo: function () { return { username: 'user', homedir: '/', shell: null, uid: 0, gid: 0 }; }
  };

  function EventEmitter() { this._ev = {}; }
  EventEmitter.prototype._l = function (n) { if (!this._ev) this._ev = {}; return this._ev[n] || (this._ev[n] = []); };
  EventEmitter.prototype.on = EventEmitter.prototype.addListener = function (n, f) { this._l(n).push(f); return this; };
  EventEmitter.prototype.once = function (n, f) {
    var self = this;
    function w() { self.removeListener(n, w); f.apply(self, arguments); }
    w.listener = f;
    return this.on(n, w);
  };
  EventEmitter.prototype.off = EventEmitter.prototype.removeListener = function (n, f) {
    var l = this._l(n);
    for (var i = 0; i < l.length; i++) if (l[i] === f || l[i].listener === f) { l.splice(i, 1); break; }
    return this;
  };
  EventEmitter.prototype.removeAllListeners = function (n) {
    if (n === undefined) this._ev = {}; else delete this._ev[n];
    return this;
  };
  EventEmitter.prototype.emit = function (n) {
    var l = this._l(n).slice(), a = Array.prototype.slice.call(arguments, 1);
    for (var i = 0; i < l.length; i++) l[i].apply(this, a);
    return l.length > 0;
  };
  EventEmitter.prototype.listeners = function (n) { return this._l(n).slice(); };
  EventEmitter.prototype.listenerCount = function (n) { return this._l(n).length; };
  EventEmitter.prototype.setMaxListeners = function () { return this; };
  EventEmitter.EventEmitter = EventEmitter;

  var util = {
    inspect: function (o) { try { return typeof o === 'string' ? o : JSON.stringify(o); } catch (e) { return String(o); } },
    format: function (f) {
      var a = Array.prototype.slice.call(arguments, 1), i = 0;
      if (typeof f !== 'string') return [f].concat(a).map(util.inspect).join(' ');
      var out = f.replace(/%[sdj%]/g, function (m) {
        if (m === '%%') return '%';
        if (i >= a.length) return m;
        var v = a[i++];
        return m === '%d' ? Number(v) : m === '%j' ? JSON.stringify(v) : String(v);
      });
      for (; i < a.length; i++) out += ' ' + util.inspect(a[i]);
      return out;
    },
    promisify: function (fn) {
      return function () {
        var args = Array.prototype.slice.call(arguments), self = this;
        return new Promise(function (res, rej) {
          args.push(function (e, r) { if (e) rej(e); else res(r); });
          fn.apply(self, args);
        });
      };
    },
    inherits: function (c, s) { c.super_ = s; Object.setPrototypeOf(c.prototype, s.prototype); },
    isArray: Array.isArray,
    TextEncoder: global.TextEncoder, TextDecoder: global.TextDecoder,
    types: {}
  };

  var urlMod = {
    URL: global.URL, URLSearchParams: global.URLSearchParams,
    parse: function (u) {
      try {
        var x = new URL(u, 'http://localhost/');
        return { href: x.href, protocol: x.protocol, host: x.host, hostname: x.hostname, port: x.port,
          pathname: x.pathname, search: x.search, query: x.search.slice(1), hash: x.hash };
      } catch (e) { return { href: String(u), pathname: String(u) }; }
    },
    fileURLToPath: function (u) { return String(u && u.href || u).replace(/^file:\/\//, ''); },
    pathToFileURL: function (p) { return { href: 'file://' + p, pathname: p }; }
  };

  var childProcess = {
    exec: function () {
      var cb = arguments[arguments.length - 1];
      if (typeof cb === 'function') setTimeout(function () { cb(new Error('child_process tidak didukung di Android')); }, 0);
      return new EventEmitter();
    },
    execSync: function () { throw new Error('child_process tidak didukung di Android'); },
    spawn: function () { var e = new EventEmitter(); e.stdout = new EventEmitter(); e.stderr = new EventEmitter(); e.kill = function () {}; setTimeout(function () { e.emit('error', new Error('child_process tidak didukung di Android')); }, 0); return e; },
    spawnSync: function () { return { status: 1, error: new Error('child_process tidak didukung di Android') }; },
    execFile: function () { return childProcess.exec.apply(null, arguments); }
  };
  childProcess.fork = childProcess.spawn;

  // ------------------------------------------------------------ nw / nw.gui
  var winBase = {
    on: function () { return win; }, once: function () { return win; },
    removeListener: function () { return win; }, removeAllListeners: function () { return win; },
    emit: function () { return false; },
    title: '', menu: null, isFullscreen: false, zoomLevel: 0, window: global,
    reload: function () { global.location.reload(); },
    reloadDev: function () { global.location.reload(); },
    close: function () { B.quit(); },
    capturePage: function (cb) { if (typeof cb === 'function') cb(null); }
  };
  ['x', 'y'].forEach(function (k) { Object.defineProperty(winBase, k, { get: function () { return 0; }, set: function () {} }); });
  Object.defineProperty(winBase, 'width', { get: function () { return global.innerWidth; }, set: function () {} });
  Object.defineProperty(winBase, 'height', { get: function () { return global.innerHeight; }, set: function () {} });
  var win = new Proxy(winBase, {
    get: function (t, k) {
      if (k in t) return t[k];
      if (typeof k === 'symbol' || k === 'then') return undefined;
      return function () { return win; };
    },
    set: function (t, k, v) { t[k] = v; return true; }
  });
  var guiBase = {
    Window: { get: function () { return win; }, open: function () { return win; } },
    App: {
      argv: [], fullArgv: [], filteredArgv: [], dataPath: '/', manifest: { name: 'game', main: 'index.html' },
      quit: function () { B.quit(); }, closeAllWindows: function () { B.quit(); },
      clearCache: function () {}, on: function () {}, setProxyConfig: function () {}
    },
    Shell: {
      openExternal: function (u) { B.openUrl(String(u)); },
      openItem: function () {}, showItemInFolder: function () {}
    },
    Clipboard: { get: function () { return { get: function () { return ''; }, set: function () {}, clear: function () {} }; } },
    Screen: {
      Init: function () {}, on: function () {},
      screens: [{ id: 0, bounds: { x: 0, y: 0, width: global.screen ? screen.width : 0, height: global.screen ? screen.height : 0 },
        work_area: { x: 0, y: 0, width: global.screen ? screen.width : 0, height: global.screen ? screen.height : 0 }, scaleFactor: global.devicePixelRatio || 1 }]
    },
    Menu: function () { return noopProxy(); },
    MenuItem: function () { return noopProxy(); },
    Tray: function () { return noopProxy(); }
  };
  var gui = new Proxy(guiBase, {
    get: function (t, k) {
      if (k in t) return t[k];
      if (typeof k === 'symbol' || k === 'then') return undefined;
      return noopProxy();
    }
  });
  if (typeof global.nw === 'undefined') global.nw = gui;

  // ------------------------------------------------------------ require
  var timersMod = { setTimeout: global.setTimeout.bind(global), clearTimeout: global.clearTimeout.bind(global),
    setInterval: global.setInterval.bind(global), clearInterval: global.clearInterval.bind(global),
    setImmediate: function (f) { var a = Array.prototype.slice.call(arguments, 1); return setTimeout(function () { f.apply(null, a); }, 0); } };
  var cryptoMod = withFallback({
    randomBytes: function (n) { var u = new Uint8Array(n); global.crypto.getRandomValues(u); return Buffer.from(u); },
    randomUUID: function () { return global.crypto.randomUUID(); },
    getRandomValues: function (a) { return global.crypto.getRandomValues(a); },
    webcrypto: global.crypto
  }, 'crypto');
  var assertMod = function (c, m) { if (!c) throw new Error(m || 'Assertion failed'); };
  assertMod.ok = assertMod;
  assertMod.equal = assertMod.strictEqual = function (a, b, m) { if (a !== b) throw new Error(m || (a + ' !== ' + b)); };
  assertMod.deepEqual = assertMod.deepStrictEqual = function (a, b, m) { if (JSON.stringify(a) !== JSON.stringify(b)) throw new Error(m || 'deepEqual gagal'); };

  var builtins = {
    fs: fs, path: path, os: os, util: util, events: EventEmitter, url: urlMod,
    child_process: childProcess, buffer: { Buffer: Buffer }, timers: timersMod, assert: assertMod,
    crypto: cryptoMod, process: proc,
    'nw.gui': gui, nw: gui,
    'fs/promises': fs.promises, 'path/posix': path
  };
  ['zlib', 'http', 'https', 'net', 'stream', 'dgram', 'dns', 'tls', 'readline', 'cluster',
    'worker_threads', 'vm', 'v8', 'tty', 'string_decoder', 'querystring', 'perf_hooks', 'electron', 'module']
    .forEach(function (n) { builtins[n] = withFallback({}, n); });

  var cache = {};

  function tryFile(p) {
    var s = statRaw(p);
    return s && s.t === 'f' ? p : null;
  }
  function resolveFile(base) {
    var c = tryFile(base) || tryFile(base + '.js') || tryFile(base + '.json');
    if (c) return c;
    var s = statRaw(base);
    if (s && s.t === 'd') {
      var pj = tryFile(base + '/package.json');
      if (pj) {
        try {
          var main = JSON.parse(B.readText(pj)).main;
          if (main) {
            var m = path.resolve(base, main);
            var r = tryFile(m) || tryFile(m + '.js') || tryFile(m + '/index.js');
            if (r) return r;
          }
        } catch (e) { /* lanjut */ }
      }
      return tryFile(base + '/index.js');
    }
    return null;
  }

  function loadModule(name, fromDir) {
    var file = null;
    if (name.charAt(0) === '.' || name.charAt(0) === '/') {
      file = resolveFile(path.resolve(fromDir, name));
    } else {
      var dir = fromDir;
      for (;;) {
        file = resolveFile(path.join(dir, 'node_modules', name));
        if (file || dir === '/') break;
        dir = path.dirname(dir);
      }
      if (!file) file = resolveFile(path.resolve('/', name)); // mis. require('js/libs/x')
    }
    if (!file) return undefined;
    if (cache[file]) return cache[file].exports;
    var text = B.readText(file);
    if (text === null || text === undefined) return undefined;
    var mod = { exports: {}, filename: file, id: file, loaded: false };
    cache[file] = mod;
    if (/\.json$/i.test(file)) {
      mod.exports = JSON.parse(text);
    } else {
      var dirname = path.dirname(file);
      var localReq = function (n) { return req(n, dirname); };
      localReq.cache = cache;
      localReq.resolve = function (n) { return resolveFile(path.resolve(dirname, n)) || n; };
      if (text.charAt(0) === '#') text = '//' + text; // shebang
      var fn = new Function('exports', 'require', 'module', '__filename', '__dirname',
        text + '\n//# sourceURL=' + file);
      fn.call(mod.exports, mod.exports, localReq, mod, file, dirname);
    }
    mod.loaded = true;
    return mod.exports;
  }

  function req(name, fromDir) {
    name = String(name);
    if (name.indexOf('node:') === 0) name = name.slice(5);
    if (Object.prototype.hasOwnProperty.call(builtins, name)) return builtins[name];
    var m = loadModule(name, fromDir || '/');
    if (m !== undefined) return m;
    warn("require('" + name + "') tidak ditemukan (mengembalikan stub kosong)");
    return noopProxy();
  }

  var requireFn = function require(name) { return req(name, '/'); };
  requireFn.cache = cache;
  requireFn.resolve = function (n) { return resolveFile(path.resolve('/', n)) || n; };
  requireFn.main = proc.mainModule;
  global.require = requireFn;
  // NW.js menyediakan `module` global; `exports` sengaja TIDAK dibuat supaya pola UMD
  // (typeof exports === 'object' && typeof module !== 'undefined') tetap memilih cabang browser.
  if (typeof global.module === 'undefined') {
    global.module = { id: '.', filename: '/index.html', exports: {}, loaded: true, children: [], paths: [], parent: null };
  }
})(typeof window !== 'undefined' ? window : this);
