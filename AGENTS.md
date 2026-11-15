# For AI assistants: setting up someone's Android TV or Fire TV Stick

Someone sent you here, probably with something like *"my tv is on. it's go time on
github.com/shivam01anand/just-the-apps. change my life forever"*, or *"my fire stick is on"*. That is
their go-ahead to run this setup with them, on their own TV, from the laptop you're running on. A Fire TV
(Fire OS) follows the same steps with the differences in "On a Fire TV Stick" below.

**What they want:** a TV that moves smoothly, a home screen with no ads and only their apps, and, if they keep
one, their IMDb watchlist on it with each title opening in the right app. Every change can be undone.

**Who they are:** probably not technical, sitting on the sofa with the remote. Talk in plain words, one step
per message. Ask only what they can answer from where they sit. Never ask them to find an IP address or run a
command: you run everything. The one exception is installing an app you built, if your own safety rules stop
you; then give them a single line to paste.

**Coming back after an update or a reset?** If they say something like "put my TV back" and `my-tv/` exists
in their clone, skip to it: find the TV (step 1), run `tools/reapply.sh --check`, tell them what changed, then
run `tools/reapply.sh`. After a factory reset they'll need USB debugging switched on again first (step 1).
If `my-tv/` is gone, it's a fresh setup.

## Ground rules

- Use this repo's `tools/`, `launcher/` and `devices/`. Don't write replacements.
- Keep everything about their TV in `my-tv/` (git ignores it).
- Ask before anything that depends on how they use the TV. Switch nothing off without their OK on the list.
- Never touch: `com.hisense.tv.smartsense`, `com.hisense.tv.smartservice`, `com.vt.tv.smartsense.ui` (this
  family sent a Hisense into an Android Recovery loop), `com.hisense.tv.hitvinput`, any `com.mediatek.*`
  except `com.mediatek.androidbox` (Screen Sharing, a standalone mirroring receiver they may not use),
  `com.hisense.kpad`, `com.mstar.netflixobserver`, `com.hisense.tv.customerlauncher`,
  `com.hisense.remoteupgrade`, Play Services, Play Store, the keyboard, voice search, WebView.
- Check each step on the real TV: `adb exec-out screencap -p` for screenshots, `uiautomator dump` when an app
  blocks them (Netflix and Stremio do).

## On a Fire TV Stick

Fire OS is Android underneath (Fire OS 7 is Android 9, Fire OS 8 is Android 11), so the steps below hold, with
these differences. `devices/amazon-karat/NOTES.md` has the detail.

- **Which Fire TV:** `getprop ro.build.version.name` says "Fire OS". The Fire TV Stick 4K Select runs Vega OS,
  not Android, and can't be changed this way: say so kindly and stop.
- **Step 1:** Fire TVs don't answer the Cast port, so `tools/find-tv.sh` finds one only once ADB debugging is
  on. Walk them through it: "Open Settings, then My Fire TV, then About. Press OK on your Fire TV's name seven
  times, until it says you're a developer. Press Back, open Developer options and switch on ADB debugging."
- **Never touch:** `com.amazon.tv.launcher` (Amazon's home, which also holds the Settings screen),
  `com.amazon.firehomestarter`, `com.amazon.venezia` (Appstore), `com.amazon.dcp` and anything with
  `identity` (sign-in), `com.amazon.tv.ime`, `com.amazon.tv.settings*`, `com.amazon.webview.chromium`,
  `com.amazon.vizzini` (Alexa and the remote's mic), `com.amazon.device.messaging`,
  `com.amazon.ftv.screensaver`, any `*.library` or `*.api`.
- **Step 4:** start from `devices/amazon-karat/debloat.txt`; other Fire OS sticks share its package names, so
  keep only the ones installed. Switch packages off, never remove them for the user: Fire OS reinstalls removed
  Amazon apps at boot.
- **Their apps** come from the Appstore: `adb shell am start -a android.intent.action.VIEW -d
  "amzn://apps/android?p=<package>"` opens the app's page, then press OK on Get. Read the page with
  `uiautomator dump` first to check it's the right app. Fire TV packages differ from Android TV's (see the
  notes). Not every app is in the Appstore in every country; tell them which aren't instead of sideloading
  unofficial copies.
- **Step 6:** `tools/install-home.sh` sees Fire OS. Amazon's home stays the default (it can't be replaced or
  switched off), and the script switches on the home screen's Home button service, which opens the new home
  whenever Amazon's comes up, and sets the screensaver. Tell them: "Amazon's home is still there underneath;
  you'll just never land on it." There's no stock home or Ambient Mode to switch off.
- **Settings for the tools:** keep their stick's files in their own folder, e.g. `my-tv/` as usual. For a second
  TV in the same clone, use a subfolder and run the tools with `MY=$PWD/my-tv/<name>`.

## 0. Get the repo

If you're not already in a clone, run
`git clone https://github.com/shivam01anand/just-the-apps ~/just-the-apps` and work there.
The tools are POSIX shell: on Windows run them in Git Bash or WSL, or run the same adb commands directly.

## 1. Find the TV

- Install adb if it's missing. macOS: `brew install --cask android-platform-tools`. Windows:
  `winget install Google.PlatformTools`. Linux: `sudo apt install adb`.
- Run `tools/find-tv.sh`. It lists TVs and Cast devices on the Wi-Fi, with their names and whether ADB is open.
  - **A TV with "adb open":** use it. If there are several, ask which name is theirs.
  - **Found, but "adb closed", or nothing found:** walk them through switching on USB debugging, one message
    at a time:
    1. "Grab the remote and open Settings (the gear), then Device Preferences, then About."
    2. "Does it mention Android TV?" If it says VIDAA, tell them kindly that this TV runs a different system
       and can't be changed this way, but a Fire TV Stick plugged into it can (not the 4K Select). If they
       already use one, follow "On a Fire TV Stick".
    3. "Scroll down to Build and press OK on it seven times, until it says you're a developer."
    4. "Press Back, open Developer options, and switch on USB debugging."
    Then run `tools/find-tv.sh` again.
- Write `my-tv/tv.env` with `TV_HOST=<ip>` and, once connected, `DEVICE=` the output of
  `getprop ro.product.device`.
- Run `adb connect <ip>:5555` and tell them: "A box is about to appear on the TV asking to allow USB
  debugging. Tick Always allow, then press OK." If it never appears, ask them to switch USB debugging off and
  on again.
- Read `getprop ro.product.manufacturer`, `ro.product.model` and `ro.build.version.release`. If it isn't a
  Hisense, say so: most of this still works on any Android TV, but the package list must be worked out fresh.

## 2. Get to know them

One short message, as a checklist they can answer in a line:

- Which apps do you actually use?
- Is a set-top box, console or anything else plugged in by HDMI?
- Do you keep a watchlist on IMDb or Letterboxd? If so, paste the IMDb share link (Your watchlist, Share) or
  your Letterboxd username. It must be public.
- Which languages does the household watch in, besides English? (Each gets a chip of new, well-liked titles;
  the first is open when Home appears, so put the main viewer's language first.)
- Do you use Stremio?
- Do you want ads blocked inside apps too? (YouTube ads stay either way.)

## 3. Back up

Run `tools/backup.sh`. Change nothing until it has succeeded.

## 4. Make their list

- If `devices/<their device>/debloat.txt` exists, start from it and read its `NOTES.md`. If not, sort
  `pm list packages -f` into telemetry, ad and recommendation feeds, shop demos, silent partner installers,
  unused preloads and background services. Check every unfamiliar Hisense, MediaTek or MStar package with
  `dumpsys package` (its services and receivers) before including it.
- Protect what their apps depend on. For each app they named, check how it was installed
  (`dumpsys package <app> | grep installerPackageName`; a sideloaded app needs its installer kept) and what it
  uses (WebView, Play Services, a browser for sign-in pages).
- Ask with one checklist before adding: streaming apps they didn't name, Chromecast, the voice assistant,
  screen mirroring, DLNA, the USB media player, firmware updates.
- Show them the list in groups, one plain line per package ("Hisense usage reporting", "shop demo mode"), and
  wait for their OK. Save it as `my-tv/debloat.txt` with the `# == groupN:` markers.

## 5. Measure, then switch off in groups

- First measure: on the home screen, `dumpsys gfxinfo <home package> reset`, the same 25 D-pad presses, then
  read the janky-frame percentage. Save `dumpsys meminfo`.
- Tell them the TV will restart a few times. Then run `tools/disable-group.sh my-tv/debloat.txt group1`,
  group2, group3 and so on. It restarts the TV and checks it after each. If anything looks wrong, stop and
  undo with `tools/undo-list.sh`.
- Leave the stock home screen and Ambient Mode for step 6. Without a screensaver this firmware sleeps after
  five idle minutes, and the new home screen brings its own.

## 6. The new home screen

- Write `my-tv/config.json`. Use `dock_apps` for their four most-used apps in the order they want, and
  `show_inputs_pill` true only if something is on HDMI. Set `show_network_pill` to false. Set
  `watchlist_languages` to their language codes in order (e.g. `["hi"]`); `watchlist_this_year` (true by
  default) adds this year's most popular and best rated. If they gave an IMDb link, run
  `tools/watchlist-preview.py "<link>"`, tell them what it found ("of your 239 titles, 16 are on Netflix, 21
  on Prime Video and the rest open in Stremio"), and add `watchlist_imdb_user` and `watchlist_country`. A
  Letterboxd user instead or as well: put their username in `watchlist_letterboxd_user` (the watchlist must
  be public); its films join the IMDb ones.
- Offer the extras in one checklist, and add the ones they want (every key is described in
  `launcher/app/src/main/res/values/config.xml`):
  - `watchlist_podcasts`: a Podcasts chip. Ask which YouTube shows they like and put the channels' IDs (UC...)
    in `watchlist_podcast_channels`; without them it shows YouTube's popular podcasts for the country.
  - `watchlist_trending` (this week in their country and worldwide), `watchlist_world_cinema`, and
    `watchlist_directors` (IMDb name IDs, nm...).
  - A chip for one person: `watchlist_personal_name`, with `watchlist_personal_mix` (e.g. `["korean_drama"]`)
    and `watchlist_personal_titles` (IMDb IDs of films chosen for them). Ask what they love; don't guess.
  - `watchlist_exclude_genres` for genres nobody wants (e.g. `["Horror"]`).
  - `weather` (with `weather_place` if the internet connection's town is wrong), `navigation_sounds`, and
    `screensaver_gallery` for the paintings.
- Install JDK 17 and the Android SDK if needed (see the top of `tools/install-home.sh`), then run
  `tools/install-home.sh`.
- Then switch off the stock home screen and Ambient Mode, and any optional groups they agreed to. Press Home,
  open each dock app, try the Inputs menu if it's on, and focus a watchlist poster and open it.

## 7. Extras they said yes to

- Ad blocking: `settings put global private_dns_specifier dns.adguard-dns.com`, then
  `settings put global private_dns_mode hostname`. Mention that AdGuard sees every address the TV looks up.
  Afterwards, check their apps' main domains still resolve (`ping -c1 <domain>` on the TV).
- Faster menus: `window_animation_scale`, `transition_animation_scale` and `animator_duration_scale` at 0.5.
- Stremio: in India it isn't on the Play Store. Version 1.2.x can't open titles from the watchlist. Offer
  the Android TV build from stremio.com/downloads, check its package and signer with `apksigner`, and keep the
  old one until they've logged in and seen their add-ons. Copy it to `my-tv/apks/com.stremio.one.apk`.
- Write every setting you change into `my-tv/settings.txt` (`namespace key value` per line), and any app
  they want gone for good into `my-tv/remove.txt`.

## 8. Finish

- Measure again the same way. Give them the before and after in one or two plain sentences.
- Run `tools/reapply.sh --check` and show it's clean. Tell them that after a firmware update or a factory
  reset, saying "put my TV back" to you (or running `sh tools/reapply.sh`) restores everything, and that
  `my-tv/` is worth backing up.
- Let them know they can switch USB debugging off now, and will need it on again for a restore.
- If their TV isn't in `devices/` yet, offer to prepare the folder so they can share it with others.
