# Straik Cam

Aplikasi Android yang langsung merekam video begitu kamu teriak **"straik"** saat mancing, tanpa menyentuh HP. Bilang **"stop"** untuk berhenti.

## Cara pakai

1. Buka aplikasi, tekan **SIAGA**, izinkan kamera, mikrofon, dan notifikasi.
2. Tekan **Atur penghemat baterai** sekali supaya siaga tidak dimatikan sistem.
3. Matikan layar. Teriak "straik" untuk mulai rekam (nada naik + getar panjang), bilang "stop" untuk berhenti (nada turun + getar dua kali).
4. Kalau lupa "stop", rekaman berhenti sendiri setelah 5 menit. Bisa juga lewat tombol **Stop rekam** di notifikasi.
5. Video tersimpan di galeri, folder `Movies/StraikCam`.

## Download APK

Setiap push ke `main` membuat APK baru lewat GitHub Actions. Ambil dari halaman **Releases** repo ini, lalu pasang di HP (izinkan "pasang dari sumber tidak dikenal").

## Pengaturan di aplikasi

- **Resolusi** 720p / 1080p / 4K dan **frame rate** 30 / 60 fps. Hanya pilihan yang didukung HP yang muncul.
- **Pre-roll**: simpan 30 detik sebelum "straik". Kamera menyala terus selama Siaga (lebih boros baterai).
- **Nada** dan **getar** saat mulai/berhenti rekam bisa dinyalakan/dimatikan. Ada tombol "Coba nada".

## Teknis

- Kotlin, Camera2 + MediaCodec (H.264 + AAC) + MediaMuxer, layanan latar depan bertipe kamera + mikrofon.
- Pre-roll: frame yang sudah dikompres disimpan di memori (buffer berputar), lalu ditulis ke MP4 mulai dari keyframe 30 detik sebelum "straik".
- Satu mikrofon (48 kHz) dipakai bersama untuk pendengar kata dan suara video.
- Pendengar kata: [Vosk](https://alphacephei.com/vosk/) offline dengan grammar terbatas ("strike", "stop"). Model diunduh saat build, tidak disimpan di repo.
- `app/debug.keystore` adalah kunci debug sekali pakai, supaya update APK bisa dipasang menimpa versi lama. Bukan untuk Play Store.

## Belum ada

Panduan baterai per merek, pengaman memori penyimpanan.
