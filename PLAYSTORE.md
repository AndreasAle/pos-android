# Yoka POS di Google Play

Aplikasi Yoka POS adalah *flavor* `yoka` dari proyek ini. Aplikasi kasir klien
(`client`, paket `com.conwebs.pos`) tidak berubah.

| | `client` | `yoka` |
| --- | --- | --- |
| Paket | `com.conwebs.pos` | `com.yokapos.kasir` |
| Nama | POS Kasir | Yoka POS |
| Server | diisi saat pertama dibuka (bawaan pos.conweb.id) | tetap `https://yokapos.com` |
| Layar awal | langsung POS | splash, onboarding, daftar / masuk / PIN |
| Tanda tangan | debug key | kunci upload Yoka |
| Jaringan | HTTP boleh (jaringan lokal) | HTTPS saja |
| targetSdk | 34 | 36 |

Semua fitur kasir sama: cetak Bluetooth/WiFi, printer dapur, mode kiosk, QRIS,
shift, PDF/Excel. Yang berbeda hanya pintu masuknya.

## Alur pengguna

```
Splash -> Onboarding (4 slide, sekali saja) -> Welcome (satu kartu di tengah)
Welcome -> Mulai Berjualan      -> form daftar native (tanpa scroll)
        -> Sudah punya akun     -> /pos        (diarahkan ke /login, lalu kembali ke POS)
        -> Masuk kasir (PIN)    -> /kasir
        -> Syarat / Privasi / Hapus Akun
Buka berikutnya: sudah login atau tablet sudah dipasangkan -> langsung POS / PIN
```

- **Form daftar** (`RegisterActivity`) memakai endpoint yang sama dengan web
  (`POST /register`, bidang dan validasi yang sama), jadi akun, verifikasi
  email, dan trial tetap diurus server. Setelah sukses, sesi login diserahkan
  ke WebView dan aplikasi lanjut ke halaman verifikasi email.
- **Aplikasi khusus kasir.** Halaman yang dibuka di dalam aplikasi dibatasi
  (`KASIR_PAGES` di `MainActivity`): POS, PIN, shift, riwayat order, struk,
  dapur, serta login/daftar/verifikasi/onboarding bisnis. Dashboard, produk,
  laporan, pengaturan, dan user tetap di web. Tautan ke sana disembunyikan,
  redirect ke `/dashboard` setelah login dialihkan ke `/pos`, dan halaman
  lain memunculkan pesan "Dibuka lewat web".
- Tidak ada layar loading bergambar; halaman web langsung tampil.

## Keputusan bisnis yang sudah diterapkan

- **Langganan tidak dijual di dalam aplikasi.** Halaman `/saas/plans`,
  `/saas/subscription`, `/saas/checkout`, dan `/subscription/expired` diblok di
  aplikasi dan diganti pesan "langganan dikelola di akun Anda". Ini menghindari
  kewajiban Google Play Billing dan tidak ada tautan keluar untuk membeli.
  Pemilik berlangganan lewat web.
- **Tombol "Masuk dengan Google" disembunyikan** di aplikasi. Google menolak
  login OAuth di dalam WebView. Email + password dan PIN kasir tetap jalan.
- **Tanpa izin lokasi, kontak, kamera, mikrofon.** Hanya Internet, Bluetooth
  (printer), dan Download.

## File & kunci

- **AAB untuk diunggah:** `app/build/outputs/bundle/yokaRelease/app-yoka-release.aab`
- APK rilis untuk pemasangan langsung: `dist/Yoka-POS-v1.0.0.apk`
- **Kunci upload:** `C:\Users\User\.yoka-keys\yoka-upload.jks` dan
  `yoka-keystore.properties` di folder yang sama. **Di luar repo, jangan
  pernah di-commit (repo ini publik).** Cadangkan kedua file ke tempat aman
  (flashdisk / password manager). Dengan *Play App Signing*, kunci upload yang
  hilang masih bisa diganti lewat dukungan Google, tapi merepotkan.

Build:

```bash
./gradlew bundleYokaRelease      # AAB untuk Play Console
./gradlew assembleYokaRelease    # APK rilis
./gradlew assembleYokaDebug      # debug, paket com.yokapos.kasir.debug
./gradlew assembleClientRelease  # aplikasi klien (tidak berubah)
```

Naikkan `versionCode` (dan `versionName`) flavor `yoka` di `app/build.gradle`
sebelum setiap unggahan ke Play.

## Yang masih harus dikerjakan pemilik

Kode sudah siap, urusan di bawah ini tidak bisa dikerjakan dari repo:

1. **Deploy web ke yokapos.com** (halaman `/privasi`, `/syarat`, `/hapus-akun`
   baru ada di repo POS, belum di server). Play meninjau URL ini, dan aplikasi
   membukanya dari layar Welcome.
2. **Isi nomor WhatsApp asli** di `site-footer.blade.php` (masih
   `08xx-xxxx-xxxx`) dan **tinjau teks hukum** di `resources/views/legal/`
   (draf, sebaiknya dibaca ulang, idealnya oleh konsultan hukum).
3. **Akun Google Play Developer** (biaya sekali bayar USD 25). Akun
   *perorangan* baru wajib uji tertutup dengan minimal 12 penguji selama 14 hari
   sebelum bisa produksi; akun *organisasi* tidak. Cek syarat terbaru di Play
   Console.
4. **Buat aplikasi di Play Console** dengan paket `com.yokapos.kasir`.
   Nama paket **tidak bisa diganti** setelah dipublikasikan.
5. **Materi toko:** ikon 512x512 (ada di `public/images/brand/yoka-icon.png`),
   *feature graphic* 1024x500, minimal 2 tangkapan layar tablet (landscape),
   deskripsi singkat dan panjang.
6. **Formulir Play Console:** Data safety (data akun, data bisnis, tidak ada
   lokasi), rating konten, target audiens (dewasa/bisnis), tautan kebijakan
   privasi `https://yokapos.com/privasi` dan URL hapus akun
   `https://yokapos.com/hapus-akun`.
7. **Akun demo untuk reviewer** (email + password, outlet berisi menu contoh),
   diisi di *App access*. Aplikasi butuh login, jadi tanpa ini review ditolak.
8. **Keputusan model langganan.** Di yokapos.com `FEATURE_SUBSCRIPTION` masih
   `false`, jadi trial tidak membatasi akses. Sebelum menjual lewat Play,
   tentukan paket, harga, dan halaman bayar di web.

## Layar & orientasi

Aplikasi dikunci *landscape* dan dirancang untuk tablet (sama seperti aplikasi
klien). Di listing, tandai sebagai aplikasi tablet; di HP kecil tampilan POS
mengikuti web responsif.
