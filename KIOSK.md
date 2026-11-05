# Mode Kiosk — Panduan Lengkap

Mode kiosk mengubah tablet menjadi **mesin kasir khusus**:

- Setelah tablet dinyalakan dan PIN tablet dimasukkan, yang muncul langsung **POS**,
  bukan layar Home Samsung.
- Tombol **Home, Back, Recent Apps** tidak bisa dipakai untuk keluar. Panel
  notifikasi tidak bisa ditarik, aplikasi lain tidak bisa dibuka.
- Setelah restart atau mati listrik, POS otomatis muncul lagi.
- PIN tablet tetap berfungsi seperti biasa.
- Hanya admin (pakai **PIN admin**) yang bisa keluar, ganti WiFi, ganti server,
  atau mematikan kiosk.

Tanpa Samsung Knox, tanpa root, tanpa langganan. Ini fitur bawaan Android
(*Lock Task Mode* + *Device Owner*).

> APK yang sama tetap bisa dipasang di tablet biasa. Selama tablet belum
> di-setup sebagai kiosk (langkah B di bawah), aplikasi berjalan normal seperti
> sebelumnya.

---

## Ringkasan alur

```
A. Siapkan laptop (sekali saja)
B. Setup tablet: factory reset → aktifkan USB debugging → pasang APK → 1 perintah ADB
C. Setup di aplikasi: buat PIN admin → server → printer → Aktifkan Mode Kiosk
D. Pakai sehari-hari
```

Waktu yang dibutuhkan: ±20–30 menit per tablet.

---

## ⚠️ Baca dulu sebelum mulai

1. **Tablet harus kosong dari akun.** Android hanya mengizinkan aplikasi menjadi
   "pemilik perangkat" (Device Owner) kalau di tablet **belum ada akun Google
   maupun akun Samsung**. Untuk tablet yang sudah dipakai, artinya
   **factory reset → semua data di tablet hilang**. Data penjualan aman karena
   tersimpan di server, bukan di tablet.
2. **Coba dulu di satu tablet cadangan**, jangan langsung di tablet yang sedang
   dipakai jualan.
3. **Simpan baik-baik file kunci tanda tangan APK** (lihat A.3). Kalau file itu
   hilang, APK kiosk **tidak bisa di-update** tanpa factory reset tablet.
4. **Catat PIN admin** di tempat aman (bukan di tablet).

---

## A. Siapkan laptop (sekali saja)

### A.1 Pasang ADB
Sudah ada kalau Android Studio terpasang:
```
C:\Users\User\AppData\Local\Android\Sdk\platform-tools\adb.exe
```
Kalau belum, unduh **SDK Platform-Tools** dari situs Android Developers, lalu
ekstrak ke mana saja.

Cek di CMD:
```bash
adb version
```
(Kalau "adb tidak dikenal", jalankan dari folder `platform-tools` di atas.)

### A.2 Siapkan APK
APK ada di `dist/POS-Kasir-VARIL-v2.7.1.apk`.

### A.3 Cadangkan kunci tanda tangan (WAJIB)
APK ditandatangani dengan file ini:
```
C:\Users\User\.android\debug.keystore
```
Salin ke flashdisk atau Google Drive. Semua update APK berikutnya **harus**
ditandatangani dengan file yang sama. Kalau berbeda, Android menolak update, dan
tablet kiosk harus di-factory reset untuk memasang versi baru.

---

## B. Setup tablet (sekali per tablet)

### B.1 Factory reset
**Setelan → Manajemen umum → Reset → Reset data pabrik.**

### B.2 Lewati semua akun di layar setup awal
Saat tablet menyala pertama kali:
- Pilih bahasa, lalu sambungkan **WiFi toko** (boleh, malah disarankan).
- Saat diminta **akun Google → pilih "Lewati"** (*Skip*).
- Saat diminta **akun Samsung → pilih "Lewati"**.
- Buat **PIN layar kunci tablet** (ini PIN untuk membuka tablet, beda dengan
  PIN admin POS).
- Selesaikan setup sampai masuk layar Home.

> Kalau tidak sengaja login akun Google/Samsung, hapus dulu di
> **Setelan → Akun dan cadangan → Kelola akun**, atau ulangi factory reset.

### B.3 Aktifkan USB debugging
1. **Setelan → Tentang tablet → Informasi perangkat lunak** → ketuk
   **Nomor bentukan** (*Build number*) **7 kali** sampai muncul "Mode pengembang
   aktif".
2. **Setelan → Opsi pengembang** → nyalakan **USB debugging**.
3. **Khusus Samsung One UI 6 ke atas:** matikan **Auto Blocker** dulu di
   **Setelan → Keamanan dan privasi → Auto Blocker**. Kalau masih menyala,
   perintah dari laptop lewat kabel USB akan diblokir.

### B.4 Sambungkan ke laptop
Colok kabel USB. Di tablet muncul **"Izinkan USB debugging?"** → centang
**Selalu izinkan dari komputer ini** → **Izinkan**.

Cek di laptop:
```bash
adb devices
```
Harus muncul satu baris berakhiran `device`. Kalau `unauthorized`, lihat layar
tablet dan izinkan.

### B.5 Pasang APK
```bash
adb install -r dist/POS-Kasir-VARIL-v2.7.1.apk
```
Harus muncul `Success`.

### B.6 Jadikan POS pemilik perangkat (perintah inti)
```bash
adb shell dpm set-device-owner com.conwebs.pos/.KioskAdminReceiver
```
Berhasil kalau muncul:
```
Success: Device owner set to package com.conwebs.pos
Active admin set to component {com.conwebs.pos/com.conwebs.pos.KioskAdminReceiver}
```

#### Kalau gagal

| Pesan | Artinya | Solusi |
| --- | --- | --- |
| `...there are already some accounts on the device` | Masih ada akun Google/Samsung | Hapus semua akun (B.2), lalu lihat baris berikutnya |
| `...was already an admin... No need to set it again` lalu error | Percobaan gagal sebelumnya meninggalkan POS sebagai "admin perangkat" | **Setelan → Keamanan dan privasi → Setelan keamanan lainnya → Aplikasi admin perangkat** → matikan **POS Kasir** → ulangi B.6 |
| `...already several users on the device` | Ada profil tamu / pengguna lain / Secure Folder | Hapus pengguna lain & Secure Folder, atau factory reset |
| `...device is already provisioned` | Tablet sudah pernah disetup dengan akun | Factory reset, ulangi dari B.1 |
| `Unknown admin` | APK belum terpasang / versi lama | Ulangi B.5 dengan APK v2.4.0 |

> Pesan "accounts" kadang muncul juga di tablet yang **baru selesai reset**,
> karena layanan Google masih bersiap. Tunggu 1–2 menit lalu ulangi B.6. Saat
> diuji di emulator Android 14, percobaan kedua berhasil.

### B.7 (Disarankan) Matikan lagi USB debugging
Lakukan setelah langkah C selesai, lewat menu admin → **Keluar Mode Kiosk
(sementara)** → Setelan → Opsi pengembang → matikan **USB debugging** → kembali
ke POS → menu admin → **Aktifkan Mode Kiosk**.

Kalau USB debugging dibiarkan menyala, siapa pun yang punya laptop yang sudah
diizinkan bisa mereset PIN admin. Kalau dimatikan, lupa PIN = factory reset.
Pilih sesuai kebutuhan toko.

---

## C. Setup di aplikasi

1. Buka **POS Kasir** (dari daftar aplikasi).
2. Isi **alamat server**: `https://pos.conweb.id` → **Simpan & Buka**.
3. Buka **menu admin**: ketuk **pojok kiri-atas layar 5× cepat** (dalam
   3 detik). Ketuk kira-kira **1 cm dari tepi**, jangan persis di ujung layar,
   karena ujung paling atas dipakai sistem untuk status bar.
4. Karena belum ada PIN, aplikasi minta **buat PIN admin** (minimal 4 angka),
   lalu ulangi sekali.
5. Di menu admin:
   - **Pilih printer struk** → pilih printer Bluetooth (printer harus sudah
     di-pair di Setelan → Bluetooth sebelum kiosk diaktifkan).
   - **Tes cetak printer**.
   - **🔒 Aktifkan Mode Kiosk.**
6. Selesai. Layar sekarang terkunci di POS.

---

## D. Pemakaian sehari-hari

### Cara membuka menu admin
| Dari mana | Caranya |
| --- | --- |
| Layar kasir (normal) | Ketuk **pojok kiri-atas 5× cepat** |
| Layar loading hijau | **Tahan lama** di layar |
| Layar "Tidak dapat terhubung ke server" | Tombol **⚙ Admin** |

Semua cara itu meminta **PIN admin**.

> Ketukan di pojok kiri-atas tetap diteruskan ke halaman POS, jadi tombol menu
> ☰ di pojok itu tetap berfungsi normal untuk kasir.

### Isi menu admin
| Menu | Fungsi |
| --- | --- |
| Pilih printer struk | Ganti printer Bluetooth / LAN |
| Tes cetak printer | Cetak slip tes |
| **Pengaturan WiFi** | Buka pilihan WiFi bawaan Android, lalu kembali ke POS |
| Ganti alamat server | Ganti alamat POS |
| 🔓 Keluar Mode Kiosk (sementara) | Membuka kunci untuk maintenance |
| 🔒 Aktifkan Mode Kiosk | Mengunci lagi |
| Ganti PIN admin | Ganti PIN admin |
| ⚠ Lepas kiosk permanen | Mengembalikan tablet ke normal sepenuhnya |

### WiFi
- **WiFi yang sudah tersimpan tersambung otomatis**, termasuk setelah router mati
  dan nyala lagi.
- **Ganti/tambah WiFi:** menu admin → **Pengaturan WiFi** → pilih jaringan → isi
  password → tekan Back untuk kembali ke POS.
- **Kalau WiFi putus**, muncul layar "Tidak dapat terhubung ke server" dengan
  tombol **📶 WiFi** (pakai PIN admin) dan **↺ Coba Lagi**.

### Maintenance (pair printer baru, update Android, atur Bluetooth, dll.)
1. Menu admin → **🔓 Keluar Mode Kiosk (sementara)** → konfirmasi.
2. Tablet kembali normal: Home, Setelan, dan semua aplikasi bisa dibuka. Kalau
   menekan Home lalu muncul pilihan "aplikasi Home", pilih **One UI Home**.
3. Lakukan yang perlu dilakukan.
4. Buka **POS Kasir** → menu admin → **🔒 Aktifkan Mode Kiosk**.

> Kiosk **tidak akan aktif sendiri** setelah dimatikan, termasuk setelah
> restart. Jangan lupa mengaktifkannya kembali.

### Restart / mati listrik
Nyalakan tablet → masukkan PIN tablet → POS langsung terbuka dalam mode kiosk.
Menu tombol power (tahan tombol power) tetap bisa dipakai untuk **Restart** dan
**Matikan**.

---

## E. Update APK di tablet kiosk

**Cara 1: lewat kabel (paling mudah)**
```bash
adb install -r dist/POS-Kasir-VARIL-vX.Y.Z.apk
```
Kiosk tetap aktif, PIN admin dan pengaturan tetap tersimpan.

**Cara 2: tanpa laptop**
Menu admin → Keluar Mode Kiosk (sementara) → kirim APK ke tablet (WhatsApp /
Drive) → pasang → buka POS → menu admin → Aktifkan Mode Kiosk.

APK baru **wajib** ditandatangani dengan kunci yang sama (A.3).

---

## F. Pemulihan kalau ada masalah

### Lupa PIN admin
Butuh laptop yang sudah pernah diizinkan USB debugging (B.4), dan USB debugging
di tablet masih menyala:
```bash
adb shell am start -n com.conwebs.pos/.MainActivity --ez reset_admin_pin true
```
Tablet menampilkan "PIN admin dihapus". Buka menu admin (5× ketuk pojok), lalu
buat PIN baru.

Kalau USB debugging sudah dimatikan: **factory reset** (lihat bawah), lalu setup
ulang dari B.

### POS error / layar putih / crash terus
1. Coba restart tablet (tahan tombol power → Restart).
2. Pasang ulang APK lewat kabel: `adb install -r ...apk`.
3. Masih gagal: factory reset.

### Mengembalikan tablet ke normal (berhenti pakai kiosk)
Menu admin → **⚠ Lepas kiosk permanen**. Tablet kembali normal sepenuhnya, dan
POS bisa di-uninstall seperti aplikasi biasa.

### Factory reset darurat (tablet benar-benar terkunci)
Selalu bisa dilakukan lewat **Recovery Mode**, kiosk tidak bisa memblokirnya:
1. Matikan tablet. Kalau tidak bisa, tahan **Power + Volume Bawah** ±10 detik.
2. Begitu layar mati, langsung tahan **Power + Volume Atas** sampai logo Samsung
   muncul, lalu lepas.
3. Di menu Recovery pilih **Wipe data/factory reset** (navigasi dengan tombol
   volume, pilih dengan tombol power).
4. Pilih **Reboot system now**.

> Tablet Samsung punya **FRP**: setelah reset, tablet minta akun Google terakhir
> yang pernah login. Karena tablet kiosk sejak awal tanpa akun (B.2), FRP tidak
> aktif. Tapi kalau pernah ada akun, siapkan email & password-nya.

### Safe Mode
Safe Mode (menahan Volume Bawah saat menyala) **diblokir** selama kiosk aktif,
supaya tidak bisa dipakai untuk melewati kiosk.

---

## G. Daftar uji (lakukan di tablet cadangan dulu)

| # | Uji | Hasil yang benar |
| --- | --- | --- |
| 1 | Tekan Home | Tetap di POS |
| 2 | Tekan Recent Apps | Tidak terjadi apa-apa |
| 3 | Tekan Back berulang di layar kasir | Tetap di POS |
| 4 | Tarik panel notifikasi dari atas | Tidak terbuka |
| 5 | Tekan tombol power (layar mati), nyalakan lagi | Minta PIN tablet → kembali ke POS |
| 6 | Restart tablet | Setelah PIN tablet, POS langsung terbuka & terkunci |
| 7 | 5× ketuk pojok kiri-atas, PIN **salah** | "PIN admin salah", menu tidak terbuka |
| 8 | 5× ketuk pojok, PIN benar → Pengaturan WiFi | Pilihan WiFi muncul, Back kembali ke POS, tetap terkunci |
| 9 | Transaksi + Cetak Struk | Struk keluar seperti biasa |
| 10 | Menu admin → Keluar Mode Kiosk | Home & aplikasi lain bisa dibuka |
| 11 | Restart dalam keadaan kiosk mati | Kiosk **tetap mati** |
| 12 | Menu admin → Aktifkan Mode Kiosk | Terkunci lagi |

---

## H. Kompatibilitas

| Android / One UI | Status |
| --- | --- |
| Android 9 ke atas (One UI 1+) | Semua fitur: status bar (baterai/WiFi/jam) terlihat, PIN tablet, menu power |
| Android 8 (One UI belum ada / Samsung Experience) | Kiosk jalan; status bar & menu power mungkin tersembunyi |
| Android 7 ke bawah | Tidak didukung (APK minimal Android 8) |

Catatan Samsung:
- **Auto Blocker** (One UI 6+) harus dimatikan saat setup lewat kabel (B.3).
- **Secure Folder** dihitung sebagai "pengguna lain" dan membuat B.6 gagal;
  jangan diaktifkan sebelum setup.
- **Knox tidak dipakai sama sekali.**

---

## I. Info teknis (untuk developer)

| Bagian | File |
| --- | --- |
| Penerima Device Owner | `app/src/main/java/com/conwebs/pos/KioskAdminReceiver.java` |
| Logika kiosk (lock task, launcher, WiFi, safe boot) | `KioskManager.java` |
| PIN admin (hash SHA-256 + salt, 20.000 putaran) | `AdminPin.java` |
| Menu admin, gesture 5× ketuk, reset PIN via ADB | `MainActivity.java` |
| Alias launcher `.KioskHome` (nonaktif kecuali kiosk menyala) | `AndroidManifest.xml` |

- Kiosk memakai `DevicePolicyManager.setLockTaskPackages`,
  `setLockTaskFeatures(SYSTEM_INFO | KEYGUARD | GLOBAL_ACTIONS)`,
  `addPersistentPreferredActivity` (HOME), `DISALLOW_SAFE_BOOT`, dan
  `startLockTask/stopLockTask`.
- Status "kiosk aktif" adalah pilihan admin yang disimpan. `onResume` hanya
  mengembalikan kiosk kalau admin memilih aktif, jadi keluar sementara tidak
  pernah aktif sendiri.
- Tidak ada BOOT_COMPLETED receiver: karena POS menjadi launcher HOME, Android
  sendiri yang membukanya setelah tablet dibuka kuncinya.
