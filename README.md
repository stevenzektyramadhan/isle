# Isle 🏝️

**Isle** adalah mini desktop audio player ultra-ringan berbentuk *floating pill / island* (mirip Dynamic Island) yang dibuat menggunakan **Rust**, **egui / eframe**, dan **rodio**.

Aplikasi ini dirancang untuk mendengarkan lagu secara langsung tanpa browser, tanpa WebView, dan hemat memori (< 50MB RAM saat memutar musik). Cukup salin (*copy*) link YouTube atau Spotify (Track, Album, Playlist), tempel atau otomatis terdeteksi dari clipboard, dan dengarkan musik Anda di latar belakang.

---

## ✨ Fitur Utama

- **Pill / Island UI Mengambang**: Tampilan minimalis berukuran kecil (`160x36 dp` saat collapsed) yang *frameless*, *transparent*, dan *always-on-top*.
- **Dashboard Interaktif**: Klik pada pill untuk memperluas menjadi dashboard (`360x130 dp`) dengan kendali penuh.
- **Dukungan Berbagai Sumber Input**:
  - **YouTube**: Link video biasa, link Shorts, atau link playlist (`https://youtube.com/playlist?...`).
  - **Spotify**: Link lagu tunggal (`track`), album (`album`), hingga playlist lengkap (`playlist`).
  - **Pencarian Teks**: Cukup ketik judul lagu atau artis (misal: `Terpukau Astrid`) untuk langsung memutar hasil terbaik.
- **Zero Disk Caching**: Audio distreaming langsung di dalam memori (`BufReader<reqwest::blocking::Response>` ke `rodio::Decoder`) tanpa menulis file sementara ke harddisk/SSD.
- **Lazy Stream Resolution**: Playlist Spotify dan YouTube dimuat secara instan ke antrean; URL audio YouTube beresolusi tinggi hanya di-extract saat lagu tersebut giliran diputar.
- **Auto Clipboard Listener**: Otomatis mendeteksi dan menawarkan pemutaran saat Anda menyalin link YouTube atau Spotify baru.
- **Dukungan Media Keys / MPRIS**: Mendukung tombol keyboard play/pause, next, previous via `souvlaki` / MPRIS Linux.
- **Posisi Window Persistent**: Posisi window di layar tersimpan otomatis di `~/.config/isle/config.json`.

---

## 🛠️ Prasyarat Sistem

Sebelum menjalankan atau meng-compile Isle, pastikan komputer Anda memiliki:

1. **Rust & Cargo** (versi stabil 1.70+):
   ```bash
   curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
   ```
2. **`yt-dlp`** (Wajib terpasang dan ada di `PATH`):
   Isle menggunakan `yt-dlp` untuk mengekstrak URL stream audio dari YouTube.
   ```bash
   # Di Arch Linux / Manjaro:
   sudo pacman -S yt-dlp

   # Di Ubuntu / Debian:
   sudo apt update && sudo apt install yt-dlp

   # Atau via pip / pipx:
   pip install -U yt-dlp
   ```
3. **Dependensi GUI & Audio Linux** (untuk Linux):
   - Alsa / PulseAudio
   - X11 / Wayland development libraries (misal: `libxkbcommon`, `libasound2-dev` pada Debian/Ubuntu).

---

## 🚀 Cara Instalasi & Menjalankan

### 1. Clone Repository
```bash
git clone https://github.com/stevenzektyramadhan/isle.git
cd isle
```

### 2. Jalankan Mode Development
```bash
cargo run
```

### 3. Build Binary Release (Direkomendasikan untuk pemakaian harian)
```bash
cargo build --release
```
Binary hasil compile akan berada di `target/release/isle`. Anda dapat menyalin file ini ke `~/.local/bin/` atau direktori sistem agar bisa dijalankan dari mana saja:
```bash
cp target/release/isle ~/.local/bin/
isle
```

---

## 📖 Cara Penggunaan & Panduan Tombol

Saat dijalankan, Isle akan muncul sebagai kapsul (*pill*) kecil di layar Anda.

### 1. Interaksi Kapsul (Collapsed State - `160x36 dp`)
- **Drag & Drop**: Klik kiri tahan dan geser untuk memindahkan posisi Isle ke mana pun di layar. Posisi akan disimpan otomatis.
- **Klik Kapsul**: Klik sekali pada area kapsul untuk membuka/memperluas ke mode **Dashboard**.
- **Indikator**: Menampilkan status (*IDLE*, *EXTRACTING...*, *PLAYING*, *PAUSED*), judul lagu, dan animasi bar equalizer saat memutar.

### 2. Interaksi Dashboard (Expanded State - `360x130 dp`)
Di mode dashboard, Anda akan melihat kontrol lengkap:

```
┌────────────────────────────────────────────────────────┐
│  [ Terpukau - Astrid                                ]  │
│  [01:14 / 04:02]  ====================○==============  │
│                                                        │
│  [⏮]  [▶ / ⏸]  [⏭]    [ ∞ AUTO ON ]    [ ✕ CLR (4) ]   │
│                                                        │
│  Input: [ Paste YouTube / Spotify URL or Search...  ]  │
└────────────────────────────────────────────────────────┘
```

#### Penjelasan Setiap Tombol:
| Tombol / Elemen | Fungsi |
|---|---|
| **`⏮` (Previous)** | Kembali ke awal lagu atau memutar lagu sebelumnya di antrean. |
| **`▶` / `⏸` (Play / Pause)** | Memulai, menjeda, atau melanjutkan lagu yang sedang diputar. |
| **`⏭` (Next)** | Melompati lagu yang sedang diputar ke lagu berikutnya di antrean. |
| **`∞` `AUTO ON` / `AUTO OFF`** | **Autoplay Toggle**:<br>• **`AUTO ON`** (Ungu menyala): Saat lagu atau antrean habis, Isle akan otomatis mencari rekomendasi lagu serupa dan memutarnya tanpa henti.<br>• **`AUTO OFF`** (Gelap): Pemutaran berhenti setelah antrean lagu selesai. |
| **`✕` `CLEAR` / `CLR (N)`** | **Clear Queue**:<br>• Menghapus seluruh daftar antrean lagu berikutnya.<br>• Label tombol menampilkan jumlah lagu di antrean, misalnya `CLR (4)` jika ada 4 lagu tersisa di antrean. |
| **Seek Bar / Slider** | Geser penunjuk waktu untuk melompat (*seek*) ke menit tertentu pada lagu. |
| **Kolom Input Teks** | Ketik judul lagu atau paste link YouTube/Spotify lalu tekan **Enter** untuk memutar. |

---

## 🧠 Cara Kerja Aplikasi (Architecture)

Isle dirancang dengan pendekatan modular dan *thread-safe*:

1. **Non-Blocking UI Thread (`src/ui.rs` & `src/main.rs`)**:
   - `eframe`/`egui` me-render antarmuka pada frame rate dinamis (hemat daya saat idle).
   - Thread antarmuka sama sekali tidak pernah menjalankan subprocess berat atau network call. Seluruh aksi dikirimkan melalui channel `std::sync::mpsc`.
2. **Audio & Worker Thread (`src/audio.rs`)**:
   - Mengontrol audio engine `rodio` via sink.
   - Mengelola antrean lagu (`VecDeque<TrackItem>`), volume, state playback, serta loop autoplay.
3. **Stream & Metadata Extractor (`src/extractor.rs`)**:
   - **YouTube**: Menggunakan `yt-dlp` untuk membaca flat playlist atau metadata, lalu mengekstrak stream URL (`-g -f ba`) secara instan.
   - **Spotify**:
     - Membaca metadata publik melalui halaman embed Spotify (`open.spotify.com/embed/{playlist|album|track}/{id}`).
     - Mengambil struktur JSON Next.js untuk mendapatkan daftar lengkap track (*title*, *artist*, *duration*).
     - Menjadwalkan pemutaran lagu tersebut ke YouTube Music/Audio secara cerdas menggunakan kueri YouTube Audio resmi (`ytsearch1:{title} {artist} Official Audio`).
4. **Zero-Copy Streaming**:
   - Aliran audio di-download secara chunk via `reqwest::blocking::Response` yang dibungkus `BufReader`, lalu langsung di-decode oleh `rodio::Decoder` langsung dari memori tanpa menyentuh disk/SSD sama sekali.
5. **Config & Memory Sync (`src/config.rs`)**:
   - Konfigurasi window position dan volume disimpan di `~/.config/isle/config.json`.

---

## 📄 Lisensi

Proyek ini dilisensikan di bawah [MIT License](LICENSE).
