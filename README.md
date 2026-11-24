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
