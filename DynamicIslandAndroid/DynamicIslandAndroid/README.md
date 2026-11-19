# Dynamic Island untuk Android (Kotlin + Jetpack Compose)

Port dari `dynamic_island_v8_3.py` (PyQt6 / Windows).

## Cara membuka
1. Buka folder ini di **Android Studio (Koala / 2024.1+)**; tunggu Gradle sync
   (wrapper memakai Gradle 8.7, JDK 17).
2. Jalankan ke perangkat fisik (Android 10+ / API 29+).
3. Di aplikasi, beri izin bertanda * (overlay + akses notifikasi) lalu nyalakan saklar.

## Pemetaan fitur
| Windows (Python)                | Android                                                         |
|---------------------------------|-----------------------------------------------------------------|
| QWidget frameless on-top        | `IslandService` + `WindowManager` (`TYPE_APPLICATION_OVERLAY`)  |
| `_step_spring`                  | Compose `spring(dampingRatio=0.70, stiffness=190)`              |
| `MediaWatcher`                  | `MediaSessionManager` + `MediaController`                       |
| `LyricsFetcher`/`CoverFetcher`  | `Net.fetchLyrics` (lrclib.net) / `Net.fetchCover` (iTunes)      |
| `NotificationWatcher`           | `IslandNotificationListener`                                    |
| `ReplySender` (UIA)             | `RemoteInput` pada `Notification.Action`                        |
| `_read_power`                   | `BatteryWatcher` (`ACTION_BATTERY_CHANGED`, `POWER_CONNECTED`)  |
| `SystemHudWatcher`              | `SystemHudWatcher` (volume, brightness, Bluetooth)              |
| `WeatherFetcher`                | `WeatherFetcher` (coroutine, open-meteo, lokasi / IP)           |
| `ClipboardWindow`               | `ClipboardManager` + `ClipboardReaderActivity`                  |
| Drop shelf                      | `ShareReceiverActivity` + `DropStore` + Share Sheet             |
| Screenshot snip                 | `ScreenshotWatcher` (MediaStore) - pratinjau screenshot sistem  |
| Auto-DND fullscreen             | `FullscreenDetector` (inset status bar) + opsional DND sistem   |

## Interaksi
- **Idle**: ketuk = buka pengaturan, tahan = Mode Fokus. Badge: Quick Drop, clipboard, fokus.
- **Musik**: ketuk = player; tahan = Mode Fokus. Geser ke atas menutup panel apa pun.
- **Notifikasi**: ketuk = buka aplikasi; tombol **Balas** = ketik balasan langsung.

## Catatan
- Posisi: atur *Posisi vertikal* dan *Celah kamera* di pengaturan agar sejajar dengan lubang kamera.
- Clipboard (Android 10+): dibaca lewat activity transparan sesaat saat clipboard berubah.
- Baterai Bluetooth memakai API tersembunyi via refleksi; tidak semua perangkat melaporkannya.
- Deteksi fullscreen adalah heuristik inset; Mode Fokus manual selalu tersedia.
