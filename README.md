# CAMO TV

Aplikasi Android streaming TV dan olahraga. Tampilannya web (`index.html`) yang dibungkus WebView, sedangkan siarannya diputar pakai pemutar native ExoPlayer (Media3).

## Fitur
- Daftar channel TV dan event olahraga, datanya dari Firebase Realtime Database
- Pemutar native: HLS (.m3u8), mendukung Referer dan User-Agent per link
- Server cadangan otomatis kalau server utama gagal
- Notifikasi, running text, dan pengaturan sistem dari admin panel

## Isi repo
| File | Fungsi |
|---|---|
| `index.html` | Tampilan aplikasi (web) |
| `MainActivity.kt` | Cangkang WebView dan jembatan ke pemutar |
| `PlayerActivity.kt` | Pemutar native ExoPlayer |
| `AndroidManifest.xml` | Pengaturan aplikasi dan izin |
| `app.gradle.kts`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties` | Pengaturan build |
| `.github/workflows/build.yml` | Build APK otomatis |

Semua file sengaja ditaruh datar di satu folder supaya gampang diupload dari HP. Saat build, struktur project Android disusun otomatis.

## Cara build APK
1. Upload atau ubah file lewat **Add file → Upload files**, lalu **Commit changes**.
2. Buka tab **Actions**, tunggu proses **Build APK** sampai centang hijau.
3. Buka run yang hijau, lalu unduh **CAMO-TV-apk** di bagian **Artifacts**.
4. Ekstrak zip, lalu install `app-debug.apk`.

Kalau build gagal, buka langkah **Build APK (debug)** dan cari baris berawalan `e:` atau `FAILURE:`.

## Format link siaran
Link biasa:
```
https://contoh.com/channel.m3u8
```
Dengan header khusus (khusus APK):
```
https://contoh.com/channel.m3u8|Referer=https://contoh.com/|User-Agent=Mozilla/5.0
```
Pakai `|Referer=none` untuk mengirim tanpa Referer.

## Update tampilan
Upload `index.html` baru dengan nama yang sama, lalu Commit. APK baru dibuat otomatis.
