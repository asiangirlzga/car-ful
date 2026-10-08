# TV Drive Lite

Tiny top-down night/desert/snow highway racer for Android TV. No libraries, no images, no sound.
Surface is fixed at 1280x720 RGB_565, so RAM stays low even on 4K TVs.

## Remote controls
| Key | Action |
|---|---|
| LEFT / RIGHT | Steer |
| UP | Accelerate |
| DOWN | Brake |
| OK | Start / Retry / Resume |
| PLAY-PAUSE or MENU | Pause |
| BACK | Menu, then Exit |

## Build the APK on GitHub
1. Create a new GitHub repo and upload everything in this folder (keep the `.github` folder).
2. Open the **Actions** tab, then run **Build APK** (it also runs on every push).
3. When it is green, open the run and download the **TVDriveLite-apk** artifact (zip containing `TVDriveLite.apk`).
4. Optional: push a tag like `v1.0` to get a direct download on the **Releases** page.

## Install on the TV
- Easiest: put the APK on a USB drive, or use a file manager / "Downloader" app on the TV (allow "Install unknown apps").
- Or ADB: `adb connect <tv-ip>` then `adb install TVDriveLite.apk`

Note: CI makes a fresh signing key each run, so uninstall the old version before installing a newer build.
