# Amazon Fire TV Stick 4K (karat), what we found

Fire OS 8.1.6.0 (Android 11), AFTMA08C15, 32-bit apps (armeabi-v7a), India. Set up 15 Nov 2026. Speed was not
measured before and after, so this page makes no speed claims.

## How the home screen works here

Fire OS has no way to pick another home screen: Home always opens Amazon's, and switching Amazon's off breaks
the stick. So Amazon's home stays, and the new home screen's **Home button** service (an accessibility service,
`HomeKeyService`) opens the new one whenever Amazon's comes up: Home, Back out of an app, after a restart.
`tools/install-home.sh` sees Fire OS and switches the service on instead of changing the default home.

- **Settings:** the standard Settings link goes through Amazon's home first, which the service would turn
  straight back into the new home. The Settings pill opens Fire TV's Settings screen directly
  (`com.amazon.tv.launcher/.ui.MainSettingsActivity`).
- **`pm clear` or a force-stop of the home screen app switches the service off.** `tools/reapply.sh` turns it
  back on.
- **The screensaver** is a normal Android screensaver on Fire OS, so the clock and gallery replace Amazon's.

## Things that bit us

- **Switching on debugging:** Settings, My Fire TV, About, press OK on the stick's name seven times, Back,
  Developer options, ADB debugging. It opens network ADB on port 5555. Fire TVs don't answer the Cast port,
  so `tools/find-tv.sh` only sees them once ADB debugging is on.
- **adb can't reach `Android/data`** (the shell user isn't in `ext_data_rw`). The tools push `config.json`
  to `Android/media/com.gathin.tvhome/` instead, and the home screen reads either.
- **Restart too soon and the change is lost.** A restart 7 seconds after `pm disable-user` brought a whole
  group back on. `tools/disable-group.sh` waits first.
- **Removing Amazon apps for user 0 doesn't stick.** Amazon Music and Silk came back at the next boot after
  `pm uninstall -k --user 0`. Switched off, they stay off.
- **After a restart** Fire OS shows "We cannot detect your remote" until a button is pressed. The remote is
  still paired.
- **Apps come from the Appstore.** `adb shell am start -a android.intent.action.VIEW -d
  "amzn://apps/android?p=<package>"` opens an app's page; press OK on Get. Its search link doesn't work: use
  the Search screen on the stick.
- **Fire TV app packages differ** from Android TV's: Prime Video `com.amazon.firebat`, YouTube
  `com.amazon.firetv.youtube`, Apple TV `com.apple.atve.amazon.appletv`, SonyLIV
  `com.onemainstream.sonyliv.android` (not `com.sonyliv`, an old listing), Zee5 `com.zee5.amazon`. The
  watchlist picks whichever is installed.
- **Zee5's Fire TV app** ignores `zee5://` links but opens zee5.com ones, so titles fall back to the web link.
- **Airtel Xstream Play** isn't in the Fire TV Appstore (India, Nov 2026). The only Airtel listing is the old
  Airtel Movies, "not available in this country".
- **Stremio** isn't in the Appstore either. The Android TV build from stremio.com/downloads (armeabi-v7a)
  installs and opens titles from the watchlist.
- **Netflix screenshots come out blank** (DRM). Use `uiautomator dump` to see where it is.
