# Lite Launcher for Android TV

A minimal home-screen replacement for Android TV. No ads, no recommendations, no background
services. It shows your apps, the time, and live **RAM** and **internal storage** usage.

## What it does

- App grid with TV banners (or icons for apps without one), sorted A to Z
- RAM and internal storage bars at the top, refreshed every 3 seconds, only while the launcher is on screen
  (green under 75%, amber 75-90%, red above 90%)
- **Pin apps:** press and hold OK on a tile to pin it to the front (marked with a star). Hold again to unpin.
- **Clean RAM** button: asks Android to stop background processes of your apps
- **Settings** button: opens the TV settings
- Icons are loaded on demand and cached, so memory use stays small
- Optional custom wallpaper (see "Add a custom wallpaper" below)
- Only dependency: `androidx.recyclerview`

## Build the APK

1. Install [Android Studio](https://developer.android.com/studio) and open the `LiteLauncher` folder.
2. Let Gradle sync. It will offer to install Android SDK 34 if you don't have it.
3. **Build > Build APK(s)** (or **Build > Generate Signed App Bundle / APK**).
   Output: `app/build/outputs/apk/debug/app-debug.apk`

For a smaller, faster build, choose the **release** variant (Build > Select Build Variant). It is shrunk
with R8 and signed with the debug key, so it installs without creating a keystore.

### Build in the cloud with GitHub Actions

Every push to `main` runs `.github/workflows/build-apk.yml`. It builds the debug APK on GitHub's
servers, so you do not need Android Studio or an SDK on your PC:

1. Open the repo's **Actions** tab and select the latest **Build Lite Launcher APK** run.
2. Download the **LiteLauncher-debug-apk** artifact (GitHub gives you a `.zip`).
3. Unzip it to get `app-debug.apk`, then install it as shown below.

You can also start a build by hand: **Actions > Build Lite Launcher APK > Run workflow**.

## Install on the TV

1. On the TV: **Settings > Device Preferences > About**, tap **Build** 7 times to enable Developer options.
2. **Developer options:** turn on **USB debugging** (and **Network debugging** / **ADB over network** if shown).
   Menu names vary between Android TV versions.
3. From your computer, on the same network:

```
adb connect <TV-IP-ADDRESS>:5555
adb install -r app-debug.apk
```

No computer handy? Copy the APK to a USB stick, open it with a file manager app on the TV, and allow
"Install unknown apps" when asked.

After installing, **Lite Launcher** appears in your TV's app row. Open it to try it out.

## Make it the default home screen

Android TV has no built-in "default launcher" setting, so use one of these:

**1. If a chooser appears when you press HOME**, pick Lite Launcher and choose **Always**.

**2. Set it with ADB** (works on many TVs):

```
adb shell cmd package set-home-activity com.litetv.launcher/.MainActivity
```

**3. If that fails, disable the stock launcher.** First find out which one is running:

```
adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
```

Common stock launchers: `com.google.android.tvlauncher` (Android TV),
`com.google.android.apps.tv.launcherx` (Google TV), `com.google.android.leanbacklauncher` (older).
Then disable only that one:

```
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

Do this only after you have confirmed Lite Launcher starts correctly. To undo it:

```
adb shell pm enable com.google.android.tvlauncher
```

## Add a custom wallpaper

The wallpaper is built into the APK, so do this **before** you build:

1. Pick a landscape image. 1920x1080 is ideal. Larger images work but make the APK bigger.
2. Rename it to exactly `wallpaper.jpg` (lowercase). A PNG works too (`wallpaper.png`), but use only one of them.
3. Put it in `app/src/main/res/drawable-nodpi/`. In Android Studio you can drag the file onto that
   folder in the Project panel. If the folder is missing, right-click `res` > **New > Directory**
   and name it `drawable-nodpi`.
4. Build the APK again and reinstall (`adb install -r ...`).

To go back to the plain gradient, delete the file and rebuild.

The launcher shrinks the image to your screen size, crops it to fit without stretching, and puts a dark
layer over it so the text and bars stay readable. To make the image brighter or darker, change the `140`
in `WALLPAPER_DIM` in `MainActivity.kt` (0 = no dimming, 255 = black).

## Good to know

- **RAM "used" looks high.** Android deliberately keeps recently used apps cached in memory so they
  reopen fast. A reading of 70-85% is normal on a TV and is not a problem by itself.
- **Clean RAM is a stopgap.** It frees memory right away, but those apps must reload when you open them
  again. Android already does this automatically when it needs memory.
- **What speeds a TV up in practice:** this launcher is light, which helps the home screen. Disabling
  the stock launcher's content recommendations and uninstalling or disabling unused apps help more.
  A launcher cannot make other apps themselves faster.
- Apps that are hidden from launchers (system services) do not appear in the grid, by design.

## Tweaking it

| Want to change | Where |
| --- | --- |
| Tile size / number of columns | `TARGET_TILE_DP` in `MainActivity.kt` |
| How often the stats refresh | `STATS_INTERVAL_MS` in `MainActivity.kt` |
| Warning thresholds and bar colors | `colorFor()` and the `COLOR_*` values in `MainActivity.kt` |
| Background and tile colors | `res/values/colors.xml` |
| Wallpaper darkness | `WALLPAPER_DIM` in `MainActivity.kt` |
| App icon and TV banner | `res/drawable/ic_launcher.xml` and `banner.xml` (banner should be 320x180) |
