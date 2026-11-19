# Straik Cam

Aplikasi Android yang langsung merekam video begitu kamu teriak **"straik"** saat mancing, tanpa menyentuh HP. Bilang **"stop"** untuk berhenti.

## Cara pakai

1. Buka aplikasi, tekan **SIAGA**, izinkan kamera, mikrofon, dan notifikasi.
2. Tekan **Atur penghemat baterai** sekali supaya siaga tidak dimatikan sistem.
3. Matikan layar. Teriak "straik" untuk mulai rekam (HP getar panjang), bilang "stop" untuk berhenti (getar dua kali).
4. Kalau lupa "stop", rekaman berhenti sendiri setelah 5 menit. Bisa juga lewat tombol **Stop rekam** di notifikasi.
5. Video tersimpan di galeri, folder `Movies/StraikCam`.

## Download APK

Setiap push ke `main` membuat APK baru lewat GitHub Actions. Ambil dari halaman **Releases** repo ini, lalu pasang di HP (izinkan "pasang dari sumber tidak dikenal").

## Teknis

- Kotlin, CameraX, layanan latar depan bertipe kamera + mikrofon.
- Pendengar kata: [Vosk](https://alphacephei.com/vosk/) offline dengan grammar terbatas ("strike", "stop"). Model diunduh saat build, tidak disimpan di repo.
- `app/debug.keystore` adalah kunci debug sekali pakai, supaya update APK bisa dipasang menimpa versi lama. Bukan untuk Play Store.

## Belum ada (langkah 3)

Pre-roll, panduan baterai per merek, bunyi bip, pengaman memori.
