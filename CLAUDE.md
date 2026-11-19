# Straik Cam: panduan untuk Claude Code

Aplikasi Android untuk pemancing: HP dipasang di holder, layar mati, lalu begitu pemancing teriak **"straik"** aplikasi langsung merekam video. Bilang **"stop"** untuk berhenti. Semuanya offline, karena di laut sering tidak ada sinyal.

Pemilik proyek: bento. Berkomunikasi dalam bahasa Indonesia santai ("gua/lu"). Pesan commit juga dalam bahasa Indonesia.

## Status saat ini (v0.1.3, 19 Nov 2026)

- Build lolos di GitHub Actions. **Belum pernah dites di HP sungguhan.** Anggap semua perilaku runtime belum terbukti.
- HP target: Redmi (HyperOS/MIUI), Samsung (One UI), Realme (realme UI). Android 10+ (minSdk 29), targetSdk 35.
- Spesifikasi lengkap: dokumen "Spesifikasi Aplikasi Straik Cam" (https://claude.ai/artifact/6avcwaEr26DTpxihp3UwSy).

## Cara build

- Rilis: setiap push ke `main` menjalankan `.github/workflows/build.yml`, yang mengunduh model Vosk, menjalankan `./gradlew assembleDebug`, lalu menerbitkan APK sebagai **GitHub Release** `v0.1.<run_number>`. Tidak ada `upload-artifact` karena kuota artifact akun GitHub penuh; jangan ditambahkan lagi.
- Lokal (butuh Android SDK): unduh `https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip`, ekstrak isinya ke `app/src/main/assets/model/` (folder ini di-gitignore), lalu `./gradlew assembleDebug`. Tanpa model, aplikasi jalan tapi Siaga langsung gagal dengan pesan "Model suara tidak ada di dalam APK".
- `app/debug.keystore` sengaja di-commit (kunci debug sekali pakai, password `android`) supaya APK baru bisa dipasang menimpa versi lama tanpa uninstall. Jangan diganti tanpa alasan.
- Versi: AGP 8.7.3, Kotlin 2.0.21, Gradle 8.11.1, Java 17. Tidak ada test otomatis.

## Arsitektur

Semua kode ada di `app/src/main/java/id/straikcam/`. UI memakai View + ViewBinding biasa (bukan Compose).

| File | Tugas |
| --- | --- |
| `MainActivity.kt` | Tombol SIAGA, izin, pilihan resolusi/fps (hanya yang didukung HP), centang pre-roll/nada/getar, tombol "Coba nada" dan "Atur penghemat baterai". |
| `StandbyService.kt` | Foreground service bertipe `camera|microphone`. Mengatur siklus siaga → rekam → simpan → siaga, auto-stop 5 menit, cooldown 2 detik, wake lock, notifikasi dengan tombol "Stop rekam" dan "Matikan siaga", arah video. |
| `AudioEngine.kt` | **Satu-satunya** pemakai mikrofon: `AudioRecord` 48 kHz mono. Tiap 100 ms data di-downsample ke 16 kHz untuk Vosk, dan (saat video di-encode) dikirim ke encoder AAC untuk suara video. |
| `VideoEngine.kt` | Camera2 → `MediaCodec` H.264 lewat input surface. Keyframe tiap 1 detik. Stabilisasi video dinyalakan kalau tersedia. |
| `ClipRecorder.kt` | Buffer berputar berisi frame yang sudah dikompres (pre-roll). Saat "straik", membuka `MediaMuxer` ke MediaStore (`Movies/StraikCam`, `IS_PENDING`), menulis mulai dari keyframe terakhir ≤ 30 detik sebelum trigger, lalu lanjut menulis frame live. Thread-safe. |
| `CameraSupport.kt` | Kemampuan kamera belakang + encoder: ukuran 16:9 yang didukung, cek 60 fps, bitrate, sumber timestamp kamera. |
| `WordListener.kt` (`object Words`) | Kata perintah, grammar Vosk, parsing hasil JSON, menyalin model dari assets ke `filesDir` sekali per versi aplikasi. |
| `Feedback.kt` | Nada (sinus lewat `AudioTrack`, usage ALARM) dan getar. Naik = mulai, turun = berhenti, tiga bip = gagal. |
| `Prefs.kt` | SharedPreferences: pre-roll, nada, getar, tinggi video, fps. |
| `AppState.kt` | `StateFlow` status yang dibaca layar dari service. |

### Aturan penting yang tidak boleh dilanggar

1. **Siaga harus dimulai dari layar yang terbuka.** Android melarang membuka kamera/mikrofon dari latar belakang, jadi jangan coba auto-start saat boot atau dari broadcast.
2. **Jangan buka mikrofon kedua.** Pendengar kata dan suara video sengaja berbagi satu `AudioRecord` di `AudioEngine`. Dua `AudioRecord` bisa membuat salah satunya mendapat suara kosong.
3. **Satu jam untuk semua timestamp.** Timestamp audio dibuat dengan `VideoEngine.nowUs()`, yang memakai jam yang sama dengan kamera (`elapsedRealtime` atau `nanoTime`, tergantung `SENSOR_INFO_TIMESTAMP_SOURCE`). Kalau ini berubah, audio dan video tidak sinkron.
4. "straik" dikenali sebagai kata Inggris **"strike"** (model Vosk bahasa Inggris, grammar terbatas `strike`, `stop`, `[unk]`).
5. Pre-roll **nyala**: kamera dan kedua encoder jalan terus selama siaga. Pre-roll **mati**: kamera dan encoder audio baru nyala saat "straik", dan buffer dikosongkan di `ClipRecorder.start()`.

## Risiko yang belum terbukti (cek dulu saat ada laporan bug)

- Pre-roll benar-benar mulai sekitar 30 detik sebelum "straik", dan suara sinkron dengan gambar.
- Arah video (`orientationHint` dari `OrientationEventListener`) benar saat HP dipasang miring. Sensor bisa berhenti saat layar mati.
- Memori: 4K 60 fps + pre-roll menyimpan sekitar 50 Mbps × 33 detik di RAM. `largeHeap` sudah nyala, tapi bisa tetap OutOfMemory.
- Kamera dan encoder mungkin dimatikan sistem saat layar mati di Redmi/Realme yang agresif soal baterai.
- Vosk bisa salah dengar karena angin, ombak, atau mesin perahu.

## Daftar kerja berikutnya

- [ ] Uji di HP bento dan perbaiki bug dari laporannya (prioritas utama).
- [ ] Layar panduan baterai per merek (Redmi: Autostart + "Tanpa batasan"; Samsung: "Aplikasi tidak pernah tidur"; Realme: aktivitas latar belakang + matikan optimasi). Buka halaman pengaturan yang tepat per merek bila bisa.
- [ ] Pengaman memori: hentikan siaga dan beri tanda kalau sisa penyimpanan < 1 GB.
- [ ] Pengaturan kepekaan pendengar kata (rendah/sedang/tinggi).
- [ ] Opsional: ganti Vosk ke Picovoice Porcupine kalau akurasi kurang (butuh akun dan AccessKey dari bento).
- [ ] Opsional: kecilkan APK (~87 MB) dengan ABI split atau unduh model saat pertama dibuka.

## Cara kerja yang diharapkan

- Ubah kode, commit dengan pesan bahasa Indonesia, push ke `main`, tunggu GitHub Actions, lalu beri bento link APK dari halaman Releases.
- Karena belum ada test, baca ulang alur thread (main, video, audio) sebelum mengubah `ClipRecorder`, `AudioEngine`, atau `VideoEngine`.
- Teks yang dilihat pengguna (layar, notifikasi, toast) dalam bahasa Indonesia.
