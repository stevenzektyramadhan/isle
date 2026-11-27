# Isle Android — Konteks Redesain UI (untuk AI agent)

> Taruh file ini di `android/ANDROID_UI_CONTEXT.md` dan referensikan dari `AGENTS.md` / prompt agent.
> Aset desain (Stitch export) taruh di `android/design/stitch/` — cukup `DESIGN.md` dan semua `screen.png`.
> **Jangan port `code.html`** (HTML/Tailwind). File itu hanya referensi visual.

## 0. Prompt pembuka (copy-paste ke agent)

```
Baca android/ANDROID_UI_CONTEXT.md sampai habis, lalu android/design/stitch/isle_audio_player/DESIGN.md
dan lihat semua screen.png. Terapkan desain itu ke UI Compose di android/app/src/main/kotlin/.../ui/
HANYA untuk fitur yang sudah ada (bagian 2). Elemen desain yang tidak punya fitur (bagian 3) jangan
dibuat. Kerjakan per fase (bagian 9), berhenti di akhir tiap fase, jalankan perintah verifikasi,
dan laporkan ringkas apa yang berubah. Jangan sentuh player/, domain/, data/, update/ kecuali
tertulis eksplisit di dokumen ini.
```

## 1. Misi dan aturan keras

**Misi:** ganti tampilan Android Isle (saat ini tema ungu, tombol teks, layout polos) dengan bahasa visual
"Isle Audio Player" (obsidian gelap + teal listrik, pill, Inter + JetBrains Mono) **tanpa mengubah perilaku**.

Aturan keras:

1. **UI saja.** Paket `player/`, `domain/`, `data/`, `update/`, `model/` tidak diubah. Satu-satunya
   pengecualian tertulis ada di bagian 9, Fase 6 (opsional dan aditif).
2. **Fitur = yang ada di kode.** Sumber kebenaran: `PlayerState` dan `PlayerCommand` (bagian 2). Kalau
   desain menampilkan sesuatu yang tidak punya state atau command, **jangan dibuat dan jangan dipalsukan**
   (tidak ada tombol mati, toggle dekoratif, atau angka karangan).
3. **Teks harus jujur.** Tidak boleh ada klaim "Lossless", "Hi-Res", "320 kbps", "Zero Buffer", "Zero Tracking",
   versi yt-dlp rekaan, dan sejenisnya (bagian 6).
4. **Tanpa dependensi berat baru.** Tidak ada Navigation Compose, Coil, Haze, `material-icons-extended`,
   atau window-size-class. Ikon: lihat bagian 5.5.
5. **Semua perintah ini harus lulus** setelah tiap fase (dari folder `android/`):
   `./gradlew testDebugUnitTest lintDebug assembleDebug`
6. Test yang ada tidak mengacu ke kelas UI (sudah dicek: tidak ada referensi ke `HomeScreen`, `PlayerScreen`,
   `HomeUiState`, `formatPlayerTime` di `src/test`), jadi UI bebas direfaktor. Test di `player/`, `domain/`,
   `data/`, `update/` tidak boleh rusak.
7. Target: `minSdk 24`, `targetSdk/compileSdk 36`, Compose BOM `2026.04.01`, Material 3. Kode memakai
   Kotlin + Compose, string UI harus lewat `strings.xml`.

## 2. Fitur yang ADA (sumber kebenaran)

Dari `model/Models.kt`:

```kotlin
TrackItem(id, title, artist: String?, originalUrl, durationSecs: Long?)   // TIDAK ada artwork/thumbnail
PlayerState(currentTrack, isPlaying, isLoading, ytdlpAvailable: Boolean?, statusMessage: String?,
  queue: List<TrackItem>, history, autoplayEnabled, autoplayAnchor: TrackItem?, autoplayFetched,
  volume: Float, elapsedSecs: Long, durationSecs: Long?, preferencesError: String?)
PlayerCommand: SubmitInput(input) | TogglePlayPause | Stop | Next | Previous | Play | Pause |
  ClearQueue | ToggleAutoplay | SetVolume(Float) | SeekTo(Long)
```

Input yang dikenali `InputClassifier`: judul lagu (pencarian), URL langsung, playlist YouTube, URL/URI Spotify.
URL jaringan privat/localhost ditolak.

Fitur yang harus tetap ada di UI baru (cek satu per satu di akhir):

| # | Fitur | Lokasi sekarang | Command / state |
|---|-------|-----------------|-----------------|
| 1 | Cari lagu / tempel link, tombol submit, IME Search | Home | `SubmitInput`, `isLoading` |
| 2 | Status loading dan pesan error/status | Home | `isLoading`, `statusMessage` |
| 3 | Kartu "Now playing" + play/pause cepat, ketuk → buka Player | Home | `currentTrack`, `isPlaying`, `TogglePlayPause` |
| 4 | Banner update aplikasi (Update / Later, error browser) | Home | `AppUpdateViewModel`, `AppUpdateNotice` |
| 5 | Player penuh: judul, artis, disc berputar saat main | Player | `currentTrack`, `isPlaying` |
| 6 | Seek slider + waktu berjalan/total (`--:--` jika durasi tidak ada, slider nonaktif) | Player | `SeekTo`, `elapsedSecs`, `durationSecs` |
| 7 | Previous / Play-Pause / Next | Player | `Previous`, `TogglePlayPause`, `Next` |
| 8 | Volume (disimpan di DataStore) | Player | `SetVolume`, `volume` |
| 9 | Autoplay on/off (disimpan di DataStore) | Player | `ToggleAutoplay`, `autoplayEnabled`, `autoplayAnchor` |
| 10 | Antrean (bottom sheet): daftar upcoming, jumlah, Clear, tutup | Player | `queue`, `ClearQueue` |
| 11 | Settings: versi app, cek update Isle, cek update yt-dlp + hasilnya | Settings | `AppUpdateViewModel`, `YtDlpUpdater.checkManually()` → `ManualUpdateResult` |
| 12 | Back handling (queue → player → settings → keluar), state tersimpan via `rememberSaveable` | MainActivity | — |
| 13 | Izin `POST_NOTIFICATIONS` (API 33+) saat start | MainActivity | — |
| 14 | `setVisible(true/false)` di `onStart/onStop` | MainActivity | **jangan dihapus** |

**Catatan:** kartu update aplikasi Isle (poin 4 dan 11) **tidak ada di desain Stitch**. Harus ditambahkan
dengan gaya yang sama (lihat 4.4). `PlayerState.preferencesError` ada tapi sekarang tidak ditampilkan di UI;
boleh ditampilkan sebagai status info kecil (opsional).

## 3. Elemen desain yang TIDAK punya fitur — keputusan

Kolom "Keputusan" mengikat. Kalau ragu, pilih **Buang**.

| Elemen di desain | Keputusan | Alasan |
|---|---|---|
| Bottom nav **Library / Search** | Buang (tanpa bottom nav) | Hanya ada satu tujuan; pencarian = Home. Ruang bawah dipakai mini player |
| Avatar/profil (kanan atas), ikon gear di layar selain Home | Buang | Tidak ada akun. Itu artefak frame prototipe (lihat 3.1) |
| Logo gambar "Isle Brand Logo" | Buang | Gambar rusak di desain. Pakai teks "Isle" atau foreground launcher |
| Breadcrumb "Isle / Home", judul "Full Player", "Queue" di top bar | Buang | Artefak frame prototipe |
| Chip "QUICK: youtu.be/… spotify.com/track…" | Ganti | Jadi teks petunjuk statis format yang didukung (bukan chip yang bisa diketuk). "Tempel dari clipboard" hanya opsional (Fase 6) |
| Chip "Recent query" | Buang | Tidak ada riwayat pencarian |
| Kartu "Lossless…" dan "Zero Buffer…" | Buang | Klaim palsu |
| Pill "QUEUE: 0 TRACKS · Dormant" | Ganti | Tampilkan hanya jika `queue` tidak kosong |
| Badge "48 kHz FLAC", "HI-RES 96K", "Hi-Res 96kHz", "Lossless ALAC", "320 kbps" | Buang | Tidak ada data kualitas audio |
| Teks "Demuxing… mirror nodes… spatial metadata", "Retry handshake", "audio node" | Buang | Jargon karangan. Pakai copy di bagian 6 |
| Badge kode `ERR_TIMEOUT` | Ganti | Boleh tampil hanya jika dipetakan dari `ExtractorError` yang dikenal, mono kecil. Selain itu buang |
| Thumbnail/album art di antrean, mini player, disc | Buang | `TrackItem` tidak punya art. Ganti nomor urut (antrean) / ikon disc (mini player) |
| Ambient backlight dari warna album | Buang | Tidak ada art. Pakai glow teal statis |
| Tombol favorit (hati) | Buang | Tidak ada fitur favorit |
| Shuffle dan Repeat | Buang | Tidak ada command-nya |
| Tombol bulat kecil di pojok disc (ikon ⊙) | Buang | Tidak jelas fungsinya |
| Drag handle (⠿) dan tombol hapus (✕) per item antrean | Buang | `QueueManager` hanya punya `clearQueue()`. Masuk backlog engine (bagian 10) |
| Menu ⋮ di item "Now playing" antrean | Buang | Tidak ada aksi |
| Tombol "Show Empty State" | Buang | Tombol debug prototipe. **Buat empty state asli** (bagian 4.3) |
| "Stream Bitrate: High/Standard" | Buang | Tidak ada pengaturan bitrate |
| "Local Audio Cache 48 MB / Clear Cache" | Buang | Tidak ada fitur cache yang bisa diatur |
| "yt-dlp is up to date (2025.02.19)" dan "Last checked 10 mins ago" | Ganti | Tampilkan **hasil nyata** `ManualUpdateResult`. Versi/waktu hanya jika sudah ada accessor read-only (Fase 6) |
| "View network fallback diagnostic" | Buang | Tidak ada fitur itu |
| "v1.4.2 (Build 240)" | Ganti | Ambil dari `PackageInfo` (`versionName`, saat ini `0.1.0`) |
| Kartu "Pure Privacy Guaranteed", pill "LOCAL-FIRST · ZERO TRACKING" | Buang | Tidak bisa dijamin (app memeriksa update via jaringan dan men-stream dari YouTube) |
| Sub-teks "Continuous mix based on initial track" | Ganti | "Lanjut memutar lagu serupa" + "berdasarkan <`autoplayAnchor.title`>" bila `autoplayAnchor != null` |
| "Session Buffer" | Ganti nama | Pakai "Queue" / "Antrean" |
| "Resolving stream · SOURCE: … (LIVE ENGINE)" | Ganti | "Mencari <input>…" + progress indeterminate |

### 3.1 Artefak frame prototipe
Setiap `screen.png` Stitch punya header berulang (logo rusak, judul layar, ikon gear, avatar) dan status bar
palsu (09:41). Itu pembungkus prototipe, **bukan bagian desain aplikasi**. Jangan diimplementasikan. Yang
diimplementasikan adalah konten di bawahnya.

## 4. Peta layar: desain → Compose

Struktur target (semua di `ui/`):

```
ui/
  theme/  IsleColors.kt  IsleType.kt  IsleShapes.kt  IsleTheme.kt
  components/  IsleIconButton.kt  PlayPauseButton.kt  IsleSlider.kt  SwitchRow.kt
               DiscView.kt  EqualizerBars.kt  StatusCard.kt  TrackRow.kt  SectionCard.kt
  home/  HomeScreen.kt  MiniPlayer.kt
  player/  PlayerScreen.kt  QueueSheet.kt
  settings/  SettingsScreen.kt
  MainActivity.kt   // hanya wiring: nav state, ViewModel, permission
  Format.kt         // formatPlayerTime (pindahkan, jangan hapus)
```

Navigasi tetap manual (3 layar + sheet) di `MainActivity` tanpa library baru. Urutan BackHandler:
**queue sheet → player → settings → keluar.** `queueOpen` diangkat ke level `MainActivity` supaya
Home **dan** Player bisa membuka `QueueSheet` yang sama (di desain, sheet dibuka dari Home lewat "View").

### 4.1 Home (gabungan 3 desain: initial, searching/error, mini-player)

Satu `HomeScreen` dengan beberapa keadaan, berdasarkan `HomeUiState` (boleh diperluas, tetap di lapisan UI).

**Selalu ada:**
- Top bar: teks "Isle" (headline, bold) di kiri, ikon gear (48dp) di kanan → Settings. Tanpa avatar/breadcrumb.
- `AppUpdateNotice` di bawah top bar, dibungkus kartu `SectionCard` (gaya baru, logika lama).
- Search field pill (`#090C0F` recessed, hairline outline, fokus = cincin teal): ikon cari di kiri, tombol
  clear (✕) saat teks tidak kosong, tombol submit bulat (→) di kanan: aktif teal saat ada teks dan tidak loading,
  redup saat tidak. IME action Search tetap. Saat `isLoading`: pakai `readOnly = true` (bukan `enabled = false`)
  supaya keyboard dan fokus tidak hilang.
- Petunjuk statis di bawah field (mono kecil, satu baris, bisa wrap): `Judul lagu · link YouTube / playlist · link Spotify`.

**Keadaan A — Idle (belum ada `currentTrack`, tidak loading, tidak ada status):**
- Hero: `DiscView` dekoratif (cincin konsentris, titik indikator di atas, tidak berputar), judul
  "Tempel link atau cari", subjudul "Ketik judul lagu, link YouTube, atau link Spotify."
- Tanpa kartu fitur dan tanpa pill antrean.

**Keadaan B — Loading (`isLoading`):**
- `StatusCard` netral: spinner kecil + "Mencari “<input>”…" + `LinearProgressIndicator` indeterminate (teal).
- Tidak ada badge kualitas dan tidak ada teks teknis.

**Keadaan C — Status/Error (`statusMessage != null && !isLoading`):**
- `StatusCard` severity error (container `errorContainer`, ikon peringatan) atau info (container surface
  container-high, ikon info). Judul + pesan hasil `mapStatus()` (bagian 6). Aksi:
  - **Dismiss:** state lokal UI (`rememberSaveable` kunci = pesan + counter submit). Tidak ada command engine
    untuk menghapus status, jadi ini hanya menyembunyikan di UI dan harus reset saat pesan baru muncul.
  - **Coba lagi:** panggil `onSubmit()` dengan `input` saat ini. Tampil hanya jika `input` tidak kosong dan
    jenis error layak dicoba ulang (timeout/gagal umum). Untuk "tidak ditemukan"/"tidak didukung" tampilkan hanya Dismiss.
  - Untuk "yt-dlp tidak tersedia": tombol "Buka Pengaturan".
- Field tidak lagi memakai `isError` merah penuh kecuali kategori error input.

**Keadaan D — Ada lagu (`currentTrack != null`):**
- Hero disc lebih kecil (±180dp, berputar saat `isPlaying`), judul (`headline-md`), artis, dan pill kecil
  autoplay bila `autoplayEnabled && autoplayAnchor != null`: `Autoplay aktif · berdasarkan <anchor.title>`.
- Baris "Up next": ikon antrean + "<n> lagu di antrean" + "Lihat ›" → `QueueSheet`. Hanya jika `queue.isNotEmpty()`.
  Di bawahnya preview maksimal 2 item (`TrackRow`: nomor urut mono, judul, artis, durasi mono atau `--:--`).
  Total "sisa waktu" hanya ditampilkan jika **semua** `durationSecs` non-null, selain itu jangan ditampilkan.
- `MiniPlayer` menempel di bawah (di atas navigation bar, `navigationBarsPadding`): ikon disc kecil,
  judul + artis 1 baris ellipsis, `PlayPauseButton` (48–56dp), tombol Next, progress tipis di tepi atas
  (`elapsedSecs/durationSecs`, disembunyikan jika durasi null). Ketuk badan mini player → buka Player.
  Latar: surface container ~92% opaque (**tanpa blur**, lihat 5.4).

### 4.2 Full Player (`full_player`)

- Baris atas: tombol collapse (chevron ↓, = `onClose`), judul tengah "Now playing" (label mono "LIVE PLAYBACK"
  hanya saat `isPlaying`, selain itu "PAUSED"), ikon antrean di kanan dengan jumlah `queue.size`.
- `DiscView` besar (±260dp, bukan 220), cincin alur 1dp putih 5%, berputar saat `isPlaying`, label tengah
  "ISLE" (mono). Tanpa "HI-FI", tanpa art. Glow teal statis di belakang (`drawBehind` radial gradient).
- Judul (`headline-md`, maks 2 baris, ellipsis), artis (`body-lg`, `onSurfaceVariant`). Tanpa hati dan tanpa
  chip kbps. Chip sumber opsional (lihat 5.6).
- Scrubber (komponen `IsleSlider`): track 4dp, thumb pill 8dp → 14dp saat ditekan, area sentuh 48dp.
  Waktu di bawahnya `label-lg` mono: elapsed kiri, total kanan (`--:--` bila `durationSecs` null/0).
  **Wajib** pakai state drag lokal dan kirim `SeekTo` sekali di `onValueChangeFinished` (bagian 7, poin 2).
- Kontrol: **Previous – Play/Pause – Next** saja (tanpa shuffle/repeat). Play/Pause 64dp, fill
  `primaryContainer`, ikon `#090C0F`, glow `rgba(0,229,201,0.25)`. Previous/Next 48dp tanpa border,
  ikon `onSurfaceVariant`. Beri jarak lega karena hanya tiga elemen.
- Kartu bawah (`SectionCard`): baris volume (ikon speaker kecil – slider – ikon speaker besar, `SetVolume`),
  garis pemisah, `SwitchRow` "Autoplay" dengan subjudul dari `autoplayAnchor` (lihat 3).
- Pill bawah "Queue (n)" + chevron ↑ → `QueueSheet`.
- **Landscape / lebar ≥ 600dp** (pakai `LocalConfiguration`, tanpa library): dua panel dengan gutter 24dp.
  Kiri: disc + judul + scrubber + kontrol. Kanan: volume, autoplay, dan daftar antrean inline. Konten tetap
  `verticalScroll`, jangan ada yang terpotong. Batasi lebar konten maks ±640dp di tablet.

### 4.3 Queue Sheet (`queue_bottom_sheet`)

`ModalBottomSheet` (bukan layar penuh; "frame" di PNG adalah prototipe). Tinggi mengikuti isi dan maksimal
±60–70% tinggi layar (jangan hard-code 500dp). Isi:
- Header: "Queue" (`headline-md`) + chip mono "<n> TRACKS" + tombol **Clear** (warna `error`, `enabled = queue.isNotEmpty()`,
  `ClearQueue`) + tombol Close 48dp.
- Kartu "Sedang diputar": `currentTrack` dengan `EqualizerBars` (animasi hanya saat `isPlaying`), tint
  `primary` 6% + garis kiri teal 1dp. Tanpa ⋮ dan tanpa badge kualitas.
- Label "UP NEXT" (mono) lalu `LazyColumn` `TrackRow`: **nomor urut** (mono, kotak 40dp rounded) → judul → artis →
  durasi mono kanan. Tidak ada drag handle, tidak ada ✕, tidak ada thumbnail. Tinggi baris 56/72dp.
- **Empty state asli:** ikon antrean + "Antrean kosong" + "Cari lagu atau tempel link playlist dari Home."
- Baris `SwitchRow` Autoplay di bawah (komponen yang sama dengan Player).

### 4.4 Settings (`settings`)

Urutan kartu (`SectionCard`, tombol back/collapse 48dp di kiri atas, judul "Settings"):

1. **Header app:** foreground launcher (bukan gambar rusak), "Isle", pill mono `v<versionName>` dari `PackageInfo`
   (hoist ke satu `remember`), deskripsi jujur (bagian 6). Tanpa "Zero tracking".
2. **Pembaruan Isle** (*tidak ada di desain, wajib*): tombol "Cek pembaruan Isle" (`appUpdate.check(true)`, disabled saat
   `checking`), hasil `Current` / `Error` / `Available` (pakai `AppUpdateNotice`), logika dari `MainActivity` lama dipindah apa adanya.
3. **Stream engine (yt-dlp):** judul + deskripsi, tombol primer pill penuh "Cek pembaruan yt-dlp"
   (`YtDlpUpdater(applicationContext).checkManually()`), indikator `checking`, hasil:
   `SUCCESS` → info "Pemeriksaan selesai", `FAILURE` → error "Gagal memperbarui", `INTEGRATION_REQUIRED` →
   info "Pembaruan belum tersedia di versi ini" (jangan tampilkan teks developer "extractor coordination").
   Pill status kanan atas "Core active" hanya jika `ytdlpAvailable == true`, "Unavailable" jika `false`, kosong jika `null`.
4. **Pemutaran (opsional):** hanya `SwitchRow` Autoplay (reuse). Jika dirasa berlebihan, hapus seluruh kartu ini.

Dibuang dari desain: bitrate, cache, diagnostic, "Pure Privacy". Jangan ganti dengan kartu kosong.

## 5. Design tokens (dari `DESIGN.md`)

`DESIGN.md` punya dua set angka yang sedikit berbeda (frontmatter M3 vs prosa). **Keputusan:** `ColorScheme`
memakai nilai frontmatter (role M3 lengkap). Nilai prosa dipakai khusus untuk komponen yang disebut eksplisit.

### 5.1 Warna → `darkColorScheme` (hanya mode gelap; abaikan tema sistem)

```kotlin
darkColorScheme(
  primary = Color(0xFF70FFE5), onPrimary = Color(0xFF00372F),
  primaryContainer = Color(0xFF00E5C9), onPrimaryContainer = Color(0xFF006155),
  inversePrimary = Color(0xFF006B5D),
  secondary = Color(0xFF44E2CD), onSecondary = Color(0xFF003731),
  secondaryContainer = Color(0xFF03C6B2), onSecondaryContainer = Color(0xFF004D44),
  tertiary = Color(0xFFD1ECFF), onTertiary = Color(0xFF00354A),
  tertiaryContainer = Color(0xFF8CD5FF), onTertiaryContainer = Color(0xFF005D7F),
  error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
  errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
  background = Color(0xFF101419), onBackground = Color(0xFFE0E2EA),
  surface = Color(0xFF101419), onSurface = Color(0xFFE0E2EA),
  surfaceVariant = Color(0xFF31353B), onSurfaceVariant = Color(0xFFB9CAC5),
  surfaceTint = Color(0xFF00DFC4),
  inverseSurface = Color(0xFFE0E2EA), inverseOnSurface = Color(0xFF2D3136),
  outline = Color(0xFF84948F), outlineVariant = Color(0xFF3B4A46),
  surfaceDim = Color(0xFF101419), surfaceBright = Color(0xFF36393F),
  surfaceContainerLowest = Color(0xFF0A0E13), surfaceContainerLow = Color(0xFF181C21),
  surfaceContainer = Color(0xFF1C2025), surfaceContainerHigh = Color(0xFF262A30),
  surfaceContainerHighest = Color(0xFF31353B),
)
```

Override khusus komponen (konstanta di `IsleColors.kt`): search field recessed `#090C0F`, glyph di tombol play
`#090C0F`, glow `Color(0xFF00E5C9).copy(alpha = 0.25f)`, hairline `Color(0xFFF1F5F9).copy(alpha = 0.08f)`,
teks utama `#F1F5F9`, sekunder `#94A3B8`.

**Aturan kontras (temuan audit sebelumnya):** teks informatif kecil minimal **4.5:1**. `#475569` (slate 600)
**hanya** untuk status disabled, jangan pernah untuk teks yang harus dibaca. Warna lama ungu `#7C3AED` dihapus total.

### 5.2 Tipografi
Inter untuk UI, JetBrains Mono untuk angka/label teknis (durasi, jumlah antrean, versi, label kecil).
Bundel font di `res/font/` (Inter 400/600/700, JetBrains Mono 500; lisensi OFL), **bukan** downloadable fonts.
Jika agent tidak bisa mengunduh, pakai `FontFamily.Default` dan `FontFamily.Monospace` dengan `// TODO` dan lapor.
Gunakan **sp**, bukan px:

| Role desain | Font | Ukuran/line | Berat | Dipakai untuk |
|---|---|---|---|---|
| display-lg-mobile | Inter | 32/40 | 700 | (cadangan) |
| headline-md | Inter | 22/28 | 600 | judul lagu, judul hero, "Queue" |
| headline-sm | Inter | 18/24 | 600 | judul kartu, judul item |
| body-lg / md / sm | Inter | 16/24, 14/20, 12/16 | 400 | teks isi, artis, deskripsi |
| label-lg / md / sm | JetBrains Mono | 13/18, 11/16, 10/14 | 500 | waktu, jumlah, badge, label seksi (letterSpacing sesuai `DESIGN.md`) |

Waktu pemutaran wajib mono + `fontFeatureSettings = "tnum"` agar lebar tidak bergeser.

### 5.3 Bentuk dan spasi
`Shapes`: sm 8dp, default 16dp, md 24dp, lg 32dp, xl 48dp, pill = `CircleShape`/`RoundedCornerShape(50)`.
Pill: tombol utama, chip, field cari, progress. Kartu dan sheet: 24–32dp. Margin layar 16dp (24dp di lebar besar),
grid 8dp, jarak dalam 4/8/16dp. **Semua target sentuh ≥ 48×48dp** walau visual lebih kecil.

### 5.4 Elevasi, glow, "glass"
- Kedalaman lewat tint permukaan (`surfaceContainerLowest → … → Highest`), bukan bayangan tebal. Hairline 1dp
  `rgba(241,245,249,0.08)` untuk kartu.
- Glow: `drawBehind` dengan `Brush.radialGradient` alpha rendah; hanya untuk tombol play dan kepala progres.
  Statis, tanpa animasi.
- **"Glass/backdrop blur" tidak ada padanannya di Compose tanpa library tambahan.** Ganti dengan surface container
  ±92% opaque. Jangan pakai `Modifier.blur` (itu mengaburkan konten sendiri, bukan latar).

### 5.5 Ikon
Gunakan `androidx.compose.material:material-icons-core` (cukup untuk: PlayArrow, Pause, SkipNext, SkipPrevious,
Settings, Search, Close, ArrowForward, KeyboardArrowDown/Up, Refresh, Info, Warning, Check, Delete).
Untuk yang tidak ada (queue_music, volume_down/up, link, graphic_eq, autoplay/radio) buat **vector drawable
XML** kecil di `res/drawable/` (Material Symbols, Apache 2.0). **Jangan** tambah `material-icons-extended`
(build release belum memakai minify, APK akan membengkak). Semua ikon punya `contentDescription` dari `strings.xml`
(atau `null` bila dekoratif).

### 5.6 Chip sumber (opsional)
Turunkan dari `track.originalUrl` host saja: `youtube.com`/`youtu.be` → "YouTube", `spotify.com` → "Spotify",
selain itu nama host. Jangan klaim kualitas atau codec. Boleh dibuang kalau membuat layout ramai.

## 6. Teks dan copy

Semua teks tampil lewat `strings.xml` (default **Inggris**, lalu `values-in/strings.xml` Indonesia). Tanpa teks
hardcoded di Composable. Gaya: singkat, manusiawi, tanpa jargon.

Deskripsi header Settings: *"Pemutar audio ringan untuk menyetel musik dari pencarian, YouTube, dan Spotify. Tanpa akun."*
(Jangan tambahkan klaim privasi/telemetri.)

`mapStatus(message: String): UiStatus` (fungsi murni di `ui/`, dengan unit test sendiri) memetakan
`statusMessage` yang sudah ada ke severity + judul + pesan + aksi. Jalankan `grep` untuk semua penghasil
`statusMessage` (`noteStatus(`, `message =`, `ExtractorError`) dan lengkapi tabel ini. **Pesan tidak dikenal →
kategori generik, pesan asli ditampilkan kecil (mono) sebagai detail.**

| Pesan engine (sekarang) | Severity | Judul (ID) | Pesan (ID) | Aksi |
|---|---|---|---|---|
| `yt-dlp is unavailable` | error | Mesin pemutar belum siap | Komponen yt-dlp tidak tersedia. Coba perbarui di Pengaturan. | Buka Pengaturan |
| `Request timed out` | error | Koneksi habis waktu | Periksa internet lalu coba lagi. | Dismiss, Coba lagi |
| `No tracks found` | info | Lagu tidak ditemukan | Coba kata kunci lain. | Dismiss |
| `This input is not supported yet` | info | Input belum didukung | Gunakan judul lagu atau link YouTube/Spotify. | Dismiss |
| `Private network addresses are not allowed` | error | Link tidak diizinkan | Alamat jaringan lokal atau privat diblokir. | Dismiss |
| `Extraction failed` / `Stream failed` | error | Gagal memutar | Lagu tidak bisa dimuat sekarang. | Dismiss, Coba lagi |
| lainnya | error | Terjadi masalah | (pesan asli, kecil) | Dismiss |

## 7. Perbaikan audit yang WAJIB ikut (jangan diulang kesalahannya)

1. **Edge-to-edge:** panggil `enableEdgeToEdge()` sebelum `setContent`. Tiap layar memakai
   `safeDrawingPadding()` (atau `statusBarsPadding` + `navigationBarsPadding` + `imePadding`). MiniPlayer ada di atas
   navigation bar. Ikon system bar terang di atas latar gelap.
2. **Seek:** jangan kirim `SeekTo` di setiap `onValueChange`. Simpan nilai drag lokal, kirim sekali di
   `onValueChangeFinished`, dan selama drag abaikan `elapsedSecs` dari engine.
3. **Rotasi disc:** baca nilai animasi di fase draw (`graphicsLayer { rotationZ = angle }`), bukan `Modifier.rotate(angle.value)`
   (itu me-recompose seluruh layar tiap frame). Hentikan rotasi (statis) jika animator duration scale = 0
   (`Settings.Global.ANIMATOR_DURATION_SCALE`). Hindari loop manual `while(isPlaying){ animateTo; snapTo }` tanpa
   pengaman itu; lebih baik `rememberInfiniteTransition` yang dikendalikan flag. Equalizer bars: aturan yang sama.
4. **Kontras:** lihat 5.1.
5. **Aksesibilitas:** `SwitchRow` memakai `Modifier.toggleable(role = Role.Switch)` pada seluruh baris (jangan
   `contentDescription` ganda pada `Switch`). Slider punya `stateDescription` ("1:23 dari 3:45"). Teks tombol/ikon dari
   `strings.xml`. Cek TalkBack mengumumkan status play/pause dan loading.
6. **Font scale 200% dan layar 320dp:** tidak boleh ada teks terpotong tanpa ellipsis atau kontrol keluar layar.
7. **Pesan status tidak lagi otomatis merah:** pakai `mapStatus()` (bagian 6).
8. **Pisahkan Settings** dari `MainActivity` ke `settings/SettingsScreen.kt`; `getPackageInfo` tidak dipanggil
   berulang di dalam komposisi.
9. Izin notifikasi tidak diubah perilakunya di tugas ini (tetap diminta di `onCreate`). Memindahkannya ke saat
   pertama memutar lagu masuk Fase 6 (opsional).

## 8. Larangan

- Jangan mengimplementasikan elemen "Buang" di bagian 3, termasuk bila "supaya mirip desain".
- Jangan menambah command/state di `player/`, `domain/`, `data/`, `update/` untuk menutup celah desain.
- Jangan menyalin Tailwind/HTML. Jangan memakai gambar/URL dari `code.html` (banyak yang rusak atau eksternal).
- Jangan mengaktifkan blur/backdrop, animasi berat, atau gradient besar yang dianimasikan.
- Jangan mengubah `AndroidManifest.xml`, `build.gradle.kts` selain: menambah `material-icons-core` dan
  (bila perlu) `android:theme` splash di manifest. Perubahan lain minta izin dulu.
- Jangan commit font/PNG besar tanpa konfirmasi ukuran (font maks ±1 MB total).

## 9. Fase kerja (berhenti di akhir tiap fase)

| Fase | Isi | Selesai bila |
|---|---|---|
| **0 Baseline** | Jalankan perintah verifikasi pada kondisi awal. Buat daftar semua `statusMessage` (grep). Catat hasil | Semua lulus. Daftar pesan ada |
| **1 Fondasi** | `theme/` (warna, tipe, bentuk), font, `strings.xml` (en + in), `enableEdgeToEdge`, komponen dasar (IconButton, PlayPauseButton, IsleSlider, SwitchRow, DiscView, EqualizerBars, StatusCard, TrackRow, SectionCard), vector drawable ikon, `mapStatus()` + unit test | Build lulus. UI lama masih jalan dengan tema baru. Test `mapStatus` hijau |
| **2 Home + Queue** | `HomeScreen` 4 keadaan, `MiniPlayer`, `QueueSheet` (diekstrak dan dibuka dari Home & Player), banner update | Poin 1–4, 10, 12 di bagian 2 berfungsi di emulator. Empty state asli ada |
| **3 Player** | `PlayerScreen` baru + landscape dua panel, seek aman, rotasi di draw phase | Poin 5–10 berfungsi. Seek tidak membanjiri engine |
| **4 Settings** | `SettingsScreen` (header, update Isle, yt-dlp, opsional Pemutaran) | Poin 11 berfungsi. Semua hasil `ManualUpdateResult` dan `AppUpdateResult` tertangani |
| **5 Polish & QA** | Aksesibilitas, font scale 200%, layar 320dp, landscape, tablet, reduce-motion, kontras, hapus kode/warna ungu lama | Checklist bagian 11 terisi |
| **6 Opsional** | Aksesor read-only versi/tanggal terakhir cek yt-dlp, tombol "Tempel dari clipboard", izin notifikasi saat pertama memutar, tipe status terstruktur di `PlayerState` | Hanya jika diminta eksplisit |

Format laporan per fase: file yang dibuat/diubah (ringkas), hasil 3 perintah Gradle, hal yang ditunda, dan
pertanyaan yang butuh keputusan. Maks. ±15 baris.

## 10. Backlog (butuh perubahan engine — di LUAR tugas ini)

Desain menyiratkan fitur ini, tetapi perlu perubahan di `QueueManager`/`PlayerCommand`/`PlayerEngine`:
ketuk item antrean untuk memutar, hapus satu item, urutkan ulang (drag), shuffle, repeat, favorit/library,
riwayat pencarian, pilihan bitrate/kualitas, cache audio dan tombol bersihkan cache, artwork/thumbnail
(`TrackItem` perlu field URL gambar). Jika pemilik proyek memintanya, kerjakan sebagai tugas terpisah **dengan test**.

## 11. Checklist akhir (isi di Fase 5)

- [ ] Semua 14 fitur di bagian 2 masih berfungsi (uji manual di emulator `x86_64` atau device `arm64-v8a`)
- [ ] Tidak ada elemen "Buang" di bagian 3 yang muncul di UI
- [ ] Tidak ada teks hardcoded di Composable; `values-in` lengkap
- [ ] Tidak ada ungu `#7C3AED` / warna lama tersisa (`grep -ri "7C3AED\|0C0A16" android/app/src`)
- [ ] Teks kecil ≥ 4.5:1, target sentuh ≥ 48dp
- [ ] Status bar/nav bar tidak menimpa konten di Android 15+ (cek Settings button dan kontrol bawah)
- [ ] Seek tidak mengirim command berulang saat drag (cek dengan log sementara, lalu hapus log)
- [ ] Disc tidak memicu recompose per frame (Layout Inspector / compose compiler metrics bila sempat)
- [ ] Rotasi layar tidak mereset input/antrean/layar aktif
- [ ] `./gradlew testDebugUnitTest lintDebug assembleDebug` hijau
- [ ] Tidak ada perubahan di `player/`, `domain/`, `data/`, `update/`, `model/` (`git diff --stat` untuk memastikan)
