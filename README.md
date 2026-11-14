# Isle 🏝️

[![CI](https://github.com/stevenzektyramadhan/isle/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/stevenzektyramadhan/isle/actions/workflows/ci.yml)

**Isle** adalah mini desktop audio player berbentuk *floating pill / island* (mirip Dynamic Island) yang dibuat menggunakan **Rust**, **egui / eframe**, dan **rodio**.

Aplikasi ini dirancang untuk mendengarkan lagu secara langsung tanpa browser, tanpa WebView, dan tanpa cache audio di disk. Cukup tempel link YouTube atau Spotify (Track, Album, Playlist), atau ketik judul lagu, lalu dengarkan musik di latar belakang.

---

## ✨ Fitur Utama

- **Pill / Island UI Mengambang**: Tampilan minimalis `290x54 dp` saat collapsed, *frameless*, *transparent*, dan *always-on-top*.
- **Dashboard Interaktif**: Klik pill untuk memperluas menjadi dashboard `360x320 dp` dengan kendali penuh.
- **UI Bersih**: Palet warna semantik terpadu, glif geometris, dan status yang jujur tanpa metrik palsu.
- **Interactive Seek Bar**: Geser atau klik timeline untuk melompat ke posisi durasi tertentu. Target di-clamp ke durasi yang diketahui; codec atau stream yang tidak mendukung seek akan menolak permintaan tersebut (lihat bagian batasan).
- **Dukungan Berbagai Sumber Input**:
  - **YouTube**: Link video, Shorts, atau playlist.
  - **Spotify**: Link track, album, atau playlist.
  - **Pencarian Teks**: Ketik judul lagu atau artis (misalnya `Terpukau Astrid`).
- **Zero Disk Caching**: Audio distreaming di memori (`BufReader<reqwest::blocking::Response>` ke `rodio::Decoder`) tanpa file audio sementara.
- **Lazy Stream Resolution**: Playlist dimuat sebagai antrean ringan; URL audio hanya di-resolve saat track tersebut akan diputar.
- **Pintasan Keyboard**: Play/pause, seek, volume, dan perpindahan mode dari keyboard.
- **Quick Mute & Volume Control**: Slider volume dengan tooltip persentase (`volume * 100`, maksimum 150%) serta klik ikon speaker untuk mute/unmute.
- **Queue Peek Tooltip**: Hover tombol antrean untuk melihat preview 5 lagu berikutnya.
- **Media Keys / MPRIS**: Tombol play/pause, next, dan previous via `souvlaki` di Linux melalui MPRIS. Di Windows, kontrol media kini memakai HWND dari `eframe` dan `souvlaki`, tetapi tetap memerlukan validasi runtime manual di Windows.
- **Posisi Window Persistent**: Posisi window disimpan di config platform (lihat bagian config).

---

## 🛠️ Prasyarat Sistem

1. **Rust & Cargo** (minimum `1.88`, sesuai `rust-version` di `Cargo.toml`):
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   ```
2. **`yt-dlp`** (wajib tersedia di `PATH`):
   Isle memakai `yt-dlp` untuk resolusi stream audio. Versi yang diuji: **2026.10.26**. Versi lebih lama tidak dijamin.
   ```bash
   # Linux, paket distro Arch / Manjaro:
   sudo pacman -S yt-dlp

   # Linux, paket distro Ubuntu / Debian:
   sudo apt update && sudo apt install yt-dlp

   # Linux, Windows, atau macOS via pip:
   pip install -U yt-dlp
   ```
   ```powershell
   # Windows via winget:
   winget install yt-dlp.yt-dlp

   # Windows via Scoop:
   scoop install yt-dlp
   ```
   ```bash
   # macOS via Homebrew:
   brew install yt-dlp
   ```
3. **Dependensi GUI & Audio Linux**:
   - ALSA / PulseAudio
   - X11 / Wayland development libraries (misalnya `libxkbcommon`, `libasound2-dev` di Debian/Ubuntu).

---

## 🚀 Cara Instalasi & Menjalankan

### Bundle Portabel Windows

Bundle portabel berisi `isle.exe`, `yt-dlp.exe`, dan `README.txt` dalam satu folder. Extract ZIP, jangan pisahkan kedua file `.exe`, lalu double-click `isle.exe`. Bundle ini tidak memerlukan Rust, Cargo, Visual Studio, Visual C++ Redistributable, hak administrator, atau pengaturan `PATH`; `yt-dlp.exe` sudah disertakan. Koneksi internet tetap diperlukan. Config Windows disimpan di `%APPDATA%\isle\config.json`.

Instruksi ini berlaku untuk bundle portabel. Build dari source tetap memerlukan `yt-dlp` yang tersedia di `PATH`, seperti dijelaskan di bagian prasyarat.

### 1. Clone Repository
```bash
git clone https://github.com/stevenzektyramadhan/isle.git
cd isle
```

### 2. Jalankan Mode Development (Linux, Windows, atau macOS)
```bash
cargo run
```

### 3. Build Binary Release
```bash
cargo build --release
```
Binary berada di `target/release/isle` pada Linux dan macOS, atau `target\release\isle.exe` pada Windows.
```bash
# Linux / macOS:
./target/release/isle
```
```powershell
# Windows:
.\target\release\isle.exe
```

### 4. Verifikasi CI dan Batas Validasi Runtime

CI mengunci build dan test pada Ubuntu, Windows, dan macOS. Pada Linux, CI juga menjalankan `cargo fmt --check` dan `cargo clippy --all-targets -- -D warnings`; pemeriksaan Rust `1.88` juga dijalankan. CI tidak memvalidasi perilaku GUI, audio, atau perangkat keras media secara nyata.

Kontrol media Windows kini menggunakan HWND-backed integration melalui `eframe`/`souvlaki`, tetapi validasi runtime native Windows belum dilakukan. Dukungan Linux menggunakan MPRIS. Belum ada klaim dukungan media key macOS.

---

## 📖 Cara Penggunaan & Panduan Tombol

Saat dijalankan, Isle muncul sebagai kapsul kecil di layar.

### 1. Interaksi Kapsul (Collapsed - `290x54 dp`)
- **Drag**: Klik kiri tahan dan geser untuk memindahkan Isle. Posisi disimpan otomatis.
- **Klik Kapsul**: Membuka dashboard.
- **Tombol Play di Kanan**: Langsung play/pause tanpa membuka dashboard.
- **Indikator**: Badge status (`PLAYING` / `IDLE`), judul, artis, dan pesan sementara seperti `Loading stream…`, error, atau status pencarian.

### 2. Interaksi Dashboard (Expanded - `360x320 dp`)
```
┌──────────────────────────────────────────────────┐
│ ● Isle                         [ PLAYING ]  ⌄    │
│                                                  │
│  ╭────╮  Terpukau                                │
│  │ ◉  │  Astrid                                  │
│  ╰────╯  Loading stream…                         │
│                                                  │
│  01:14  ─────────────●──────────────  04:02      │
│              AUTOPLAY MIX                        │
│                                                  │
│   [∞ AUTO ON] [⏮] [▶/⏸] [⏭] [✕ CLR (4)]        │
│   [🔇] ─────●──────────────── [🔊]  100%        │
│   [🔍 Search song, YouTube, or Spotify URL… × →] │
└──────────────────────────────────────────────────┘
```

#### Penjelasan Setiap Tombol:
| Tombol / Elemen | Fungsi |
|---|---|
| **`⏮` (Previous)** | Memutar track sebelumnya dari history; track saat ini didorong ke depan queue. Jika history kosong, tidak terjadi apa-apa (tidak mengulang track saat ini). |
| **`▶` / `⏸` (Play / Pause)** | Memulai, menjeda, atau melanjutkan track. |
| **`⏭` (Next)** | Mengambil track berikutnya dari queue. |
| **`∞` `AUTO ON` / `AUTO OFF`** | **Autoplay Toggle**: `AUTO ON` mencari rekomendasi serupa saat lagu/antrean habis; `AUTO OFF` berhenti setelah antrean selesai. |
| **`✕` `CLEAR` / `CLR (N)`** | Menghapus queue berikutnya. `N` adalah jumlah track; hover untuk preview 5 track. |
| **Seek Bar** | Klik/geser untuk seek. Hanya aktif jika durasi diketahui; target di-clamp ke durasi. Codec yang menolak seek atau stream tanpa dukungan HTTP Range tidak dapat di-seek. |
| **Speaker & Volume Slider** | Klik speaker untuk mute/unmute. Tooltip menampilkan `volume * 100`; `100%` berarti gain `1.0`, maksimum `150%` berarti gain `1.5`. |
| **Kolom Input & `×` / `→`** | Ketik judul atau tempel URL, lalu Enter atau `→`. `×` membersihkan input. |

#### ⌨️ Pintasan Keyboard
`Space` dan panah aktif saat kolom pencarian tidak fokus; `Esc` dan `Ctrl + Shift + Space` tetap aktif selama jendela Isle memiliki fokus.

| Shortcut | Aksi |
|---|---|
| **`Space`** | Play / Pause |
| **`←` / `→`** | Seek mundur / maju 5 detik; target di-clamp ke durasi bila diketahui |
| **`↑` / `↓`** | Volume +/− `0.075` gain, yaitu 7,5 poin persentase; clamp `0–150%` |
| **`Esc`** | Menciutkan dashboard ke pill |
| **`Ctrl + Shift + Space`** | Buka/tutup dashboard, **hanya saat jendela Isle memiliki fokus** (bukan hotkey global) |

---

## 🧠 Cara Kerja Aplikasi (Architecture)

1. **Non-Blocking UI Thread (`src/ui.rs` & `src/main.rs`)**:
   - `eframe`/`egui` me-render UI; tidak ada subprocess atau network call di thread UI.
   - Semua aksi dikirim lewat channel `std::sync::mpsc`.
2. **Audio & Worker Thread (`src/audio.rs`)**:
   - Mengontrol `rodio` sink, queue (`VecDeque<TrackItem>`), volume, status playback, dan autoplay.
   - History dibatasi 200 track; state hanya dipublikasikan ke UI dan media keys saat berubah.
3. **Stream Resolver (`src/extractor.rs`)**:
   - Mengklasifikasikan URL media, playlist, atau teks pencarian.
   - Resolusi stream didelegasikan ke `yt-dlp`.
   - Playlist dimuat sebagai antrean ringan; stream di-resolve saat track akan diputar.
4. **Zero Disk Caching**:
   - Audio di-stream bertahap melalui `BufReader` ke `rodio::Decoder` di RAM; tidak ada file audio sementara.
5. **Jaringan**:
   - Client HTTP memakai `connect_timeout` 10 detik dan timeout 10 detik per operasi baca/range. Timeout dihitung per operasi, bukan sebagai deadline total stream, sehingga stream panjang tidak dipotong selama data terus mengalir.
6. **Cookie Browser (fallback)**:
   - Isle pertama mencoba resolver tanpa cookie. Jika gagal, Isle mencoba `--cookies-from-browser` untuk browser yang tersimpan di config, lalu mencoba Firefox, Brave, Chrome, dan Chromium.
   - Nama browser yang berhasil dipakai diingat selama sesi berjalan. Config hanya menyimpan nama browser (`cookies_browser`), bukan isi cookie.
   - **Peringatan privasi:** cookie browser dapat memberi akses ke sesi YouTube yang sedang login. `yt-dlp` membacanya secara lokal; gunakan fallback ini hanya jika diperlukan.
7. **Config per OS (`src/config.rs`)**:
   - Linux: `~/.config/isle/config.json` (menghormati `XDG_CONFIG_HOME`)
   - macOS: `~/Library/Application Support/isle/config.json`
   - Windows: `%APPDATA%\isle\config.json`
   - Config disimpan saat ada perubahan (periodik) dan saat aplikasi keluar.

### Memori (terukur, bukan klaim)
Diukur pada Linux (PipeWire/ALSA, shared library NVIDIA + Mesa), sampel `VmRSS` dari `/proc/<pid>/status` setiap 1–2 detik; `PSS` dari `smaps_rollup`. Idle memakai binary release; kondisi playback memakai build release dari source yang sama.

| Kondisi | Sampel | RSS min | RSS rata-rata | RSS puncak |
|---|---:|---:|---:|---:|
| Idle | 16 × 1 dtk | 137.520 KB (134,3 MiB) | 137.539 KB (134,3 MiB) | 137.552 KB (134,3 MiB) |
| Lagu pendek (212 dtk, 45 dtk playback) | 30 × 1 dtk | 141.684 KB (138,4 MiB) | 142.038 KB (138,7 MiB) | 142.156 KB (138,8 MiB) |
| Stream panjang (MP3 ~6 menit, 5 menit playback) | 150 × 2 dtk | 140.712 KB (137,4 MiB) | 141.167 KB (137,9 MiB) | 141.460 KB (138,1 MiB) |

RSS puncak pada pengukuran Linux sekitar **139 MiB** dan didominasi shared library GPU/LLVM (`libLLVM` ~25,8 MB, `libnvidia-gpucomp` ~19,8 MB, `gallium` ~12,2 MB). Private dirty sekitar 29–30 MB; playback hanya menambah sekitar **4–5 MB** dibanding idle. PSS idle ~76 MiB dan ~109 MiB saat stream panjang. Angka RSS sangat bergantung pada driver/GPU dan bukan ukuran heap aplikasi. Pengukuran ini hanya mencakup Linux, bukan klaim performa lintas platform.

---

## 📄 Lisensi

Proyek ini dilisensikan di bawah [MIT License](LICENSE).
