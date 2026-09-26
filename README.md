# Bambu Spool Reader

Aplikasi Android kecil untuk membaca tag RFID spool Bambu Lab dan menampilkan **nama warna resmi** (mis. "Jade White", "Charcoal"), jenis filamen, kode varian, hex, berat, suhu, dan tanggal produksi.

## Keamanan
- Izin hanya `NFC`. **Tidak ada izin INTERNET**, jadi aplikasi tidak bisa mengirim data ke mana pun.
- **Hanya membaca** (`readBlock`), tidak pernah menulis ke tag.
- Tanpa library pihak ketiga; hanya framework Android.

## Cara kerja
1. Kunci MIFARE per sektor diturunkan dari UID tag (HKDF-SHA256), sesuai riset
   [Bambu-Research-Group/RFID-Tag-Guide](https://github.com/Bambu-Research-Group/RFID-Tag-Guide).
2. Blok 1, 2, 4, 5, 6, 12, 14, 16 dibaca dan di-parse (`SpoolData.java`).
3. Nama warna dicari (`ColorDb.java`) di `app/src/main/assets/colors.tsv`, dengan urutan prioritas:
   1. material ID + kode warna dari variant ID (tabel resmi Bambu Studio)
   2. material ID + semua hex warna (resmi)
   3. material ID + hex warna pertama (resmi, ditandai "cek ulang")
   4. data komunitas untuk variant ID lama (ditandai "cek ulang")

Sumber tabel resmi: `resources/profiles/BBL/filament/filaments_color_codes.json` di repo BambuStudio.
Diuji terhadap 5.458 dump tag asli ([Bambu-Lab-RFID-Library](https://github.com/queengooborg/Bambu-Lab-RFID-Library)):
kunci cocok 5.458/5.458, nama warna cocok 99,3%.

## Build
Setiap push ke `main`, GitHub Actions menjalankan tes parser (`test/ParserTest.java`), mem-build APK,
lalu menerbitkannya di **Releases**.

## Kunci tanda tangan
Tidak ada kunci yang disimpan di repo. Selama secret `KEYSTORE_B64` dan `KEYSTORE_PASSWORD` belum diisi,
CI membuat kunci sekali pakai di setiap build. Akibatnya, **hapus aplikasi lama sebelum memasang versi baru**.
Supaya update bisa langsung dipasang di atas versi lama, isi kedua secret itu
(Settings → Secrets and variables → Actions) dengan keystore PKCS12 (alias `spoolreader`) dalam format base64.

## Memperbarui daftar warna
Unduh ulang `filaments_color_codes.json` dari BambuStudio, lalu buat ulang `colors.tsv`
(kolom: fila_id, color_code, colors, fila_type, nama EN, sumber).

## Lisensi & atribusi
Proyek ini dirilis di bawah **GNU AGPL-3.0** (lihat `LICENSE`), karena memuat data turunan dari:
- **Bambu Studio** (© Bambu Lab, AGPL-3.0): tabel nama warna di `app/src/main/assets/colors.tsv`
  diturunkan dari `resources/profiles/BBL/filament/filaments_color_codes.json`.
- **[Bambu-Lab-RFID-Library](https://github.com/queengooborg/Bambu-Lab-RFID-Library)** (GPL-3.0):
  dump tag di `test/fixtures/` dan entri berlabel `komunitas` di `colors.tsv`.
- Format tag dan cara menurunkan kunci mengikuti dokumentasi
  [Bambu-Research-Group/RFID-Tag-Guide](https://github.com/Bambu-Research-Group/RFID-Tag-Guide).

Proyek ini tidak berafiliasi dengan Bambu Lab.
