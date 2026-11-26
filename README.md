# RPGM WebView APK

Bungkus game RPG Maker MV/MZ jadi APK (WebView, landscape) lewat GitHub Actions.
Tombol virtual digambar lewat kode (tanpa gambar), bisa di-hide/unhide.

## Cara pakai (Termux)

```bash
pkg install unzip git gh      # sekali saja, kalau belum ada
unzip rpgm-webview.zip && cd rpgm-webview
bash push.sh nama-repo-kamu   # buat repo private, push, tunggu build, unduh APK ke ./apk
```

Atau manual: `git init -b main && git add -A && git commit -m init && gh repo create NAMA --private --source=. --push`,
lalu `gh run watch`, `gh run download -n RPGM-APK`.

## Ganti isi

| Yang diganti | Caranya |
|---|---|
| Game | Kosongkan `game/`, salin game kamu ke sana (folder hasil deploy Windows/Web). Folder yang berisi `index.html` dicari otomatis, jadi struktur MV (`www/`) maupun MZ (rata) sama-sama jalan. File NW.js (`*.exe`, `*.dll`, `*.pak`, `locales/`, `node_modules/`) dibuang otomatis. |
| Icon | Timpa `icon.png` di root (persegi, 512x512 atau 1024x1024) |
| Splash | Timpa `splash.png` di root (tampil 1,7 detik lalu fade; ubah `SPLASH_MS` di `MainActivity.java`) |
| Nama app / package ID | `config.properties` |
| Game terlalu besar untuk di-push | Upload zip game ke tempat yang punya link langsung, lalu jalankan workflow manual: `gh workflow run "Build APK" -f game_url=https://...zip` |

## Tombol virtual

- D-pad 8 arah (geser jari antar arah), **OK** (Z), **BATAL** (X), **DASH** (Shift), **MENU** (Esc), **PGUP**, **PGDN**, **CTRL** (lewati teks), **TAB**.
- Tombol **KURSOR** (atas, sebelah PGDN): mode mouse virtual untuk game dengan kursor kustom. D-pad menggerakkan kursor (tahan = makin cepat, DASH = lebih cepat), **OK** = klik kiri, **BATAL** = klik kanan. Tap lagi untuk kembali ke mode normal.
- Tombol **...** membuka baris ekstra: ENTER, SPACE, F2 (FPS), F3 (stretch), F9 (debug, hanya mode test).
- Ikon **mata** di pojok kanan atas: tap = sembunyikan/tampilkan semua tombol. **Tahan** = menu pengaturan (ukuran, transparansi, getar, muat ulang, log error JS, keluar).
- Tombol Back Android = Esc.
- Menyentuh area kosong tetap diteruskan ke game (tap-to-move MV/MZ), dan bisa bersamaan dengan tombol (multitouch).

## Catatan penting

- Game jalan dalam **mode browser**: sengaja tidak ada `require`/`process`, jadi `Utils.isNwjs()` bernilai false dan save game memakai `localStorage` (MV) / IndexedDB (MZ). Plugin yang memaksa `require('fs')`, `nw.gui` atau `child_process` tidak akan jalan; lihat menu **Log error JS** untuk melacaknya.
- Android membedakan huruf besar/kecil nama file. Server aset sudah punya fallback tanpa membedakan huruf, jadi `Img/Foo.PNG` tetap ketemu `img/foo.png`.
- APK ditandatangani `signing/release.jks` (password `rpgmwebview`), jadi update APK bisa langsung menimpa tanpa kehilangan save. Kalau repo-mu publik dan mau dirilis serius, buat keystore sendiri.
- Batas GitHub: file tunggal maksimal 100 MB. Repo publik berarti game-mu terlihat publik, gunakan `--private`.
- Mode test (F9 debug): ubah `START_URL` di `MainActivity.java` menjadi `.../index.html?test`.
- Kalau layar splash terpotong/terlalu kecil, ubah `ScaleType.FIT_CENTER` ke `CENTER_CROP` di `MainActivity.java`.

## Profil Tyrano (TyranoScript / Tyrano Builder)

Isi `PROFILE=tyrano` di `config.properties`. Tombol yang tampil:

- **PUTAR**: ganti landscape / portrait (pilihan diingat).
- **KURSOR**: mode mouse virtual. Saat aktif muncul D-pad (gerak kursor), **KIRI / KANAN / TENGAH** (tombol mouse, bisa ditahan untuk drag) dan **SCR UP / SCR DN** (roda mouse, tahan = berulang). Gerak kursor juga memicu efek hover.
- **ENTER** (lanjut teks) dan **BACK** (Esc). Tombol Back Android juga = Esc.
- **Mata** di kanan atas: sembunyikan / tampilkan semua tombol (tahan = pengaturan).

## Tombol CHEAT (profil rpgm)

Buka baris ekstra lewat tombol **...** lalu tap **CHEAT**. Daftar saklar dan variabel dibaca otomatis dari `data/System.json` game.

- Tab **Saklar**: centang = ON, hilangkan centang = OFF (langsung berlaku di game).
- Tab **Variabel**: tap salah satu, isi angka atau pakai tombol -100 / -10 / -1 / +1 / +10 / +100, lalu **Set**.
- Kolom cari menyaring berdasarkan nama atau nomor. **Segarkan** memuat ulang nilai terbaru dari game.
- Game harus sudah dimulai (New Game atau muat save), karena saklar dan variabel baru ada setelah itu.
- Semua tombol selain D-pad pada profil rpgm sekarang berukuran 3/4 (`BTN_SCALE` di `VirtualPad.java`).

## Emulasi Node.js / NW.js

Game MV/MZ versi PC sering memanggil `require('fs')`, `require('path')`, `nw.gui`, `process.platform`, dst. Di WebView tidak ada Node, jadi APK menyisipkan `nodeshim.js` ke `index.html` (aktif otomatis untuk profil `rpgm`).

- `require` mendukung `fs`, `path`, `os`, `util`, `events`, `url`, `buffer`, `crypto` (dasar), `nw.gui`, plus file `.js`/`.json` relatif dan `node_modules` milik game.
- `fs` membaca file game (folder `www`) dan menulis ke penyimpanan app (`files/nodefs`), jadi plugin yang menyimpan file (mis. save/config) tetap jalan. Hapus/ubah file bawaan game hanya berlaku pada salinan tulis.
- `process` sengaja berupa fungsi, bukan object, supaya `Utils.isNwjs()` RPG Maker tetap `false` dan inti MV/MZ tetap berjalan di mode browser (aman).
- `nw.Window.get()`, `nw.Shell.openExternal`, `nw.App.quit` tersedia (sebagian stub). `process.exit()` menutup app.
- Tidak didukung: `child_process`, modul native (`.node`), `http`/`net`/`zlib` sungguhan. Modul tak dikenal mengembalikan stub kosong dan mencatat `[nodeshim] require('x') ...` di Log error JS (tahan tombol mata).
- Matikan dengan `NODE_SHIM=false` di `config.properties` (profil `tyrano` default mati).
