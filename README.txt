ISLE

CARA MEMASANG (DISARANKAN)

1. Jalankan Isle-Setup-x64.exe.
2. Ikuti wizard pemasangan. Installer tidak memerlukan hak administrator dan memasang Isle per-user di %LOCALAPPDATA%\Programs\Isle.
3. Setelah selesai, jalankan Isle dari shortcut Start Menu atau ikon Desktop. Tidak ada jendela terminal yang ikut terbuka.

Saat aplikasi berjalan, klik pill untuk membuka dashboard, lalu gunakan tombol tutup (X) di kanan atas dashboard untuk keluar.

UPDATE OTOMATIS

Versi terpasang mengecek rilis terbaru maksimal sekali sehari. Saat ada versi baru, badge UPDATE muncul di dashboard; klik untuk mengunduh, memverifikasi, dan memasangnya. Isle akan menutup dan terbuka lagi otomatis, dan pengaturan Anda tetap tersimpan.

Mencabut pemasangan: Settings > Apps > Isle > Uninstall, atau jalankan unins000.exe di folder pemasangan.

BUNDLE PORTABEL (ALTERNATIF)

Isi folder bundle:
- isle.exe
- yt-dlp.exe
- README.txt

Cara menjalankan:
1. Extract ZIP ke folder pilihan Anda.
2. Jangan pisahkan isle.exe dan yt-dlp.exe. Keduanya harus tetap berada di folder yang sama.
3. Double-click isle.exe untuk menjalankan Isle.

Bundle ini sudah menyertakan yt-dlp.exe dan otomatis terdeteksi dari folder yang sama, sehingga tidak memerlukan Rust, Cargo, Visual Studio, Visual C++ Redistributable, hak administrator, atau pengaturan PATH.

Koneksi internet diperlukan untuk mencari dan memutar audio.

Pengaturan Isle disimpan di:
%APPDATA%\isle\config.json

Jika Anda membuat Isle dari source code, ikuti instruksi di README.md. Build dari source tetap memerlukan yt-dlp yang tersedia di PATH.
