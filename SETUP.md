# POS Kasir — Aplikasi Android

Aplikasi kasir untuk tablet Android. Isinya bukan aplikasi terpisah dengan
database sendiri — ini **layar penuh yang membuka POS dari server Laravel**,
jadi setiap transaksi dari tablet langsung masuk ke database yang sama dengan
yang dibuka lewat browser di komputer.

Konsekuensinya: laporan, stok, shift, dan saldo selalu satu angka, dari mana pun
transaksinya dibuat.

---

## Pasang ke tablet

APK siap pakai ada di:

```
dist/POS-Kasir-VARIL-v2.4.0.apk
```

**Cara paling gampang — tanpa kabel:**

1. Kirim file APK ke tablet lewat WhatsApp, Google Drive, atau email
2. Buka file itu di tablet
3. Android akan menolak dengan "Demi keamanan..." → ketuk **Setelan** →
   aktifkan **Izinkan dari sumber ini**
4. Ketuk **Instal**

**Lewat kabel USB:**

1. Aktifkan **Opsi Pengembang** di tablet (Setelan → Tentang → ketuk
   *Nomor bentukan* 7 kali), lalu nyalakan **USB Debugging**
2. Sambungkan tablet, jalankan:

```bash
adb install -r dist/POS-Kasir-VARIL-v2.4.0.apk
```

---

## Pemakaian pertama

Saat pertama dibuka, aplikasi menanyakan **alamat server**. Isi:

```
https://pos.conweb.id
```

Cukup alamat servernya saja — aplikasi menambahkan `/pos` sendiri. Kalau kamu
terlanjur menempel alamat lengkap seperti `https://pos.conweb.id/pos`, aplikasi
merapikannya otomatis.

Untuk pengujian di jaringan lokal (server jalan di komputer):

```
http://192.168.1.10:8000
```

Ganti dengan IP komputermu — cek lewat `ipconfig` di CMD. Tablet dan komputer
harus berada di WiFi yang sama.

Setelah itu, tinggal login pakai akun kasir. Sesinya tersimpan, jadi tidak perlu
login ulang setiap membuka aplikasi.

---

## Kebiasaan pemakaian

| Yang dilakukan | Hasilnya |
| --- | --- |
| **Tekan Kembali** | Mundur satu halaman di POS |
| **Tekan Kembali 2× cepat** di halaman awal | Keluar aplikasi |
| **Tahan lama** di layar loading | Buka Pengaturan: server, printer, tes cetak |
| Selesai transaksi / ketuk **Cetak** | Struk langsung keluar di printer yang dipilih |
| Ketuk **PDF** / **Export Excel** | Berkas masuk ke folder **Download** tablet |

Aplikasi berjalan layar penuh tanpa status bar, dan layar tidak akan mati
sendiri selama aplikasi terbuka — cocok untuk tablet yang berdiri di meja kasir
seharian.

---

## Printer struk (tanpa RawBT)

Mulai v2.2.0 aplikasi mengirim struk **langsung** ke printer thermal, jadi
aplikasi RawBT tidak perlu dipasang lagi. Hampir semua printer nota 58/80mm
(Epson, Xprinter, Panda, Iware, merek tanpa nama) bisa dipakai karena semuanya
memakai bahasa ESC/POS.

1. **Printer Bluetooth:** nyalakan printer, pasangkan (pair) lewat Setelan →
   Bluetooth di tablet (PIN biasanya `0000` atau `1234`).
2. Di POS, selesaikan satu transaksi lalu cetak. Saat pertama kali, aplikasi
   menanyakan printer mana yang dipakai:
   - **Printer Bluetooth** → pilih dari daftar printer yang sudah di-pair
   - **Printer WiFi / LAN** → isi IP printer (port 9100 otomatis)
   - **Printer sistem Android** → dialog cetak bawaan (printer kantor / PDF)
3. Aplikasi langsung mencetak slip tes. Kalau keluar, selesai — pilihan itu
   diingat.

Ganti printer kapan saja: tahan lama di layar loading → **Pengaturan**, atau
ketuk **Ganti Printer** saat muncul pesan gagal cetak.

Di pengaturan struk di website, metode cetak "browser" maupun "RawBT" sama-sama
jalan — di aplikasi keduanya dicetak langsung.

---

## Mode kiosk

Untuk mengunci tablet supaya hanya bisa membuka POS, lihat **[KIOSK.md](KIOSK.md)**.

---

## Yang perlu disiapkan di sisi server

Tidak ada. Aplikasi ini memakai halaman POS yang sudah ada apa adanya, termasuk:

- QRIS dinamis dengan nominal terkunci
- Pemindai barcode (scanner USB/Bluetooth yang mengetik seperti keyboard)
- Shift kasir, hold order, split payment
- Struk termal 80mm

Satu-satunya syarat: kasir harus **membuka shift** dulu sebelum bisa transaksi —
sama persis seperti di browser.

---

## Build ulang APK

Butuh **JDK 17** dan **Android SDK** (platform 34). Gradle sudah termasuk lewat
wrapper, jadi tidak perlu dipasang terpisah.

```bash
./gradlew assembleRelease
```

Hasilnya di `app/build/outputs/apk/release/app-release.apk`.

Untuk versi debug (bisa dipasang berdampingan dengan yang release karena nama
paketnya berbeda):

```bash
./gradlew assembleDebug
```

Kalau `local.properties` belum ada atau salah, buat berisi lokasi SDK-mu:

```properties
sdk.dir=C\:\\Users\\User\\AppData\\Local\\Android\\Sdk
```

---

## Catatan penandatanganan

APK ini ditandatangani dengan **debug key** bawaan Android. Artinya:

- ✅ Bisa dipasang dan dipakai normal di tablet mana pun
- ✅ Cukup untuk pemakaian internal dan demo
- ❌ **Tidak bisa** diunggah ke Google Play
- ⚠️ Kalau nanti dibuat keystore sendiri, aplikasi harus **dicopot dulu** sebelum
  memasang versi baru — Android menolak update yang tanda tangannya berbeda

Untuk rilis ke Play Store nanti, buat keystore sendiri lalu ganti
`signingConfig signingConfigs.debug` di `app/build.gradle`.
