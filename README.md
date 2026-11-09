# Isle 🏝️

**Isle** adalah mini desktop audio player ultra-ringan berbentuk *floating pill / island* (mirip Dynamic Island) yang dibuat menggunakan **Rust**, **egui / eframe**, dan **rodio**.

Aplikasi ini dirancang untuk mendengarkan lagu secara langsung tanpa browser, tanpa WebView, dan hemat memori (< 50MB RAM saat memutar musik). Cukup salin (*copy*) link YouTube atau Spotify (Track, Album, Playlist), tempel atau otomatis terdeteksi dari clipboard, dan dengarkan musik Anda di latar belakang.

---

## ✨ Fitur Utama

- **Pill / Island UI Mengambang**: Tampilan minimalis berukuran kecil (`160x36 dp` saat collapsed) yang *frameless*, *transparent*, dan *always-on-top*.
- **Dashboard Interaktif**: Klik pada pill untuk memperluas menjadi dashboard (`360x130 dp`) dengan kendali penuh.
- **Hallmark-Compliant Clean UI**: Desain antarmuka modern bebas emoji dengan palet warna semantik terpadu, glif geometris presisi, dan salinan status yang jujur tanpa metrik palsu.
- **Interactive Seek Bar**: Geser atau klik langsung pada timeline bar untuk melompat (*seek*) ke posisi durasi tertentu secara real-time.
- **Dukungan Berbagai Sumber Input**:
  - **YouTube**: Link video biasa, link Shorts, atau link playlist (`https://youtube.com/playlist?...`).
  - **Spotify**: Link lagu tunggal (`track`), album (`album`), hingga playlist lengkap (`playlist`).
  - **Pencarian Teks**: Cukup ketik judul lagu atau artis (misal: `Terpukau Astrid`) untuk langsung memutar hasil terbaik.
- **Zero Disk Caching**: Audio distreaming langsung di dalam memori (`BufReader<reqwest::blocking::Response>` ke `rodio::Decoder`) tanpa menulis file sementara ke harddisk/SSD.
- **Lazy Stream Resolution**: Playlist Spotify dan YouTube dimuat secara instan ke antrean; URL audio YouTube beresolusi tinggi hanya di-extract saat lagu tersebut giliran diputar.
- **Pintasan Keyboard Terintegrasi**: Kontrol pemutaran, volume, seek, dan perpindahan mode langsung dari keyboard tanpa menyentuh mouse.
- **Quick Mute & Volume Control**: Slider volume halus dengan persentase tooltip serta klik langsung pada ikon speaker untuk mute/unmute instan.
- **Queue Peek Tooltip**: Arahkan kursor ke tombol antrean untuk melihat preview 5 lagu berikutnya.
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
   Isle memanfaatkan utilitas media backend eksternal (`yt-dlp`) yang terpasang di sistem pengguna untuk menangani resolusi stream audio.
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
| **`✕` `CLEAR` / `CLR (N)`** | **Clear Queue**:<br>• Menghapus seluruh daftar antrean lagu berikutnya.<br>• Label tombol menampilkan jumlah lagu di antrean, misalnya `CLR (4)`.<br>• Arahkan kursor (*hover*) untuk melihat preview 5 lagu berikutnya. |
| **Seek Bar / Slider** | Klik atau geser penunjuk waktu untuk melompat (*seek*) langsung ke posisi durasi lagu. |
| **Speaker & Volume Slider** | Klik ikon speaker untuk **Mute / Unmute** instan; geser slider volume untuk mengatur tingkat suara (dilengkapi persentase tooltip). |
| **Kolom Input Teks & `×`** | Ketik judul lagu atau paste link YouTube/Spotify lalu tekan **Enter** atau tombol **`→`**. Klik tombol **`×`** untuk membersihkan kueri pencarian dengan sekali klik. |

#### ⌨️ Pintasan Keyboard (Shortcuts)
Saat kolom pencarian tidak sedang fokus mengetik, Anda dapat mengontrol Isle langsung melalui keyboard:

| Shortcut | Aksi |
|---|---|
| **`Space`** | Play / Pause |
| **`←` (Panah Kiri)** | Mundur (*seek backward*) 5 detik |
| **`→` (Panah Kanan)** | Maju (*seek forward*) 5 detik |
| **`↑` (Panah Atas)** | Naikkan volume +7.5% |
| **`↓` (Panah Bawah)** | Turunkan volume -7.5% |
| **`Esc`** | Menciutkan (*minimize*) Dashboard kembali ke Pill |
| **`Ctrl + Shift + Space`** | Buka / tutup Dashboard dari mana saja di aplikasi |

---

## 🧠 Cara Kerja Aplikasi (Architecture)

Isle dirancang dengan arsitektur modular, *thread-safe*, dan hemat sumber daya:

1. **Non-Blocking UI Thread (`src/ui.rs` & `src/main.rs`)**:
   - `eframe`/`egui` me-render antarmuka pada frame rate dinamis (hemat daya saat idle).
   - Thread antarmuka sama sekali tidak pernah menjalankan subprocess berat atau network call. Seluruh aksi dikirimkan melalui channel `std::sync::mpsc`.
2. **Audio & Worker Thread (`src/audio.rs`)**:
   - Mengontrol audio engine `rodio` via sink.
   - Mengelola antrean lagu (`VecDeque<TrackItem>`), sinkronisasi volume, state playback, serta loop radio/autoplay otomatis.
3. **Pluggable Metadata & Stream Resolver (`src/extractor.rs`)**:
   - **Input Classification**: Mengklasifikasikan kueri input secara cerdas (URL media publik, playlist, atau teks pencarian artis/judul).
   - **Delegated Stream Resolution**: Resolusi stream audio didelegasikan ke utilitas media eksternal yang tersedia di sistem pengguna, memisahkan logika aplikasi inti dari backend ekstraksi.
   - **Lazy Loading**: Playlist dimuat secara instan sebagai antrean judul ringan; resolusi stream audio hanya dilakukan tepat saat track tersebut akan diputar untuk meminimalkan beban jaringan.
4. **Zero Disk Caching**:
   - Audio di-stream secara bertahap (*chunked memory stream*) via `BufReader` langsung ke decoder audio (`rodio::Decoder`) di RAM tanpa pernah menulis file sementara ke harddisk atau SSD.
5. **Config & Memory Sync (`src/config.rs`)**:
   - Pengaturan pengguna, tingkat volume, dan koordinat jendela disimpan secara persisten di `~/.config/isle/config.json`.

---

## 📄 Lisensi

Proyek ini dilisensikan di bawah [MIT License](LICENSE).
