# Just the apps

**Your Android TV or Fire TV Stick, minus everything that isn't your apps.** It moves without stutter, the home screen
has no ads, and it opens on something worth watching: new picks in your language, what's trending this week,
world cinema, the great directors, new episodes of the YouTube shows you like, and your IMDb or Letterboxd
watchlist by genre. Pick a poster and it opens in whichever app has it. When the TV rests, it hangs a painting.

![The stock Hisense home screen with an Apple TV promotion, Shop and Discover tabs and a sponsored row, next to the new home screen: a row of animated films under genre chips, the time and weather at the top, and Netflix, YouTube, Stremio and Prime Video below](docs/before-after.jpg)

## Get started

Turn the TV on, open [Claude Code](https://claude.com/claude-code) on a laptop on the same Wi-Fi, and say:

```
my tv is on. it's go time on https://github.com/shivam01anand/just-the-apps. change my life forever
```

It takes it from there.

> **If you're an AI assistant and someone sent you this link:** clone this repository and follow
> [AGENTS.md](AGENTS.md) with them.

## On a Fire TV Stick

Samsung, LG, Roku and Hisense VIDAA TVs can't run any of this, but a Fire TV Stick plugged into one can. On
the stick, turn on ADB debugging (Settings, My Fire TV, About, press OK on its name seven times, then
Developer options), and say:

```
my fire stick is on. it's go time on https://github.com/shivam01anand/just-the-apps. change my life forever
```

Everything below works the same, with two differences:

- **Amazon's home screen stays, you just never land on it.** Fire OS won't let you choose another home, so
  the new one comes with a small Home button service: press Home, back out of an app or restart the stick, and
  you're on your screen. Settings opens Fire TV's own settings.
- **Your apps come from the Amazon Appstore**, and the watchlist opens titles in their Fire TV versions
  (Prime Video, Apple TV, SonyLIV, Zee5, YouTube). Stremio comes from stremio.com.

Not the Fire TV Stick 4K Select: it runs Amazon's Vega OS, not Android, and only takes Appstore apps. Tested on
a Fire TV Stick 4K ([notes](devices/amazon-karat/NOTES.md)).

## Who's watching?

A family TV can have one home per person. List them under `profiles` in `config.json` and the TV asks "Who's
watching?" when it wakes, with whoever watched last already chosen, and Menu brings it back any time. Each person
gets either this home screen (`"home": "watchlist"`, the rest of `config.json`) or a set of channels
(`"home": "channels"`): rows of films and series built from IMDb IDs, the one you're on filling the top of the
screen, rows of favourite directors or stars that open their best films, and rows that remember the next film in
a series. The TV looks up each title's details, a still and the app it plays in by itself, once a week.

```json
"profiles": [
  {"id": "mum", "name": "Dolly", "home": "watchlist", "tagline": "Lupin, TVF, K-dramas", "color": "#7A2E3B"},
  {"id": "dad", "name": "Vikas", "home": "channels", "card_still": "tt0079221", "channels": [
    {"name": "Comedies like Golmaal", "titles": ["tt0079221", "tt0072783"]},
    {"name": "James Bond, in order", "numbered": true, "titles": ["tt0055928", "tt0057076"]},
    {"name": "Action like Top Gun", "sort": "rating", "titles": ["tt1745960", "tt0117500"]},
    {"name": "Favourite stars", "role": "actor", "people": ["nm0000821", "nm0000125"]}
  ]}
]
```

The screensaver can also hang the household's own paintings: list them under `screensaver_own`. While a painting
is up, Left and Right choose between the masters, a mix (one of yours after every two) and only your own;
`tools/own-paintings.sh masters | mix | own` sets the same from a computer. Back on any home goes to
"Who's watching?".

## Why this exists

> "I watch TV less because I hate the slow start-up and the laggy scrolling. I just want the apps."

That was the brief, from the owner of a three-year-old Hisense 55U6G. The panel was fine. What sat around
the apps was not: a home screen with a sponsored row and Shop and Discover tabs, a shop demo mode, a data
saver nobody turned on, a partner installer that adds apps by itself, and ten apps waking up in the minute
after every start. The home screen alone used 117 MB on a TV with under 2 GB of memory.

## What changes

**A home screen that's only your apps.** Your four favourites sit in a row along the bottom, with the rest
in a grid below. There are no sponsored rows, tabs or recommendations from apps you don't use.

**Moving around feels instant.** On a 55U6G, janky frames on the home screen dropped from 24% to 3.3%, and
the slowest frames from 65 ms to 18 ms. We pressed the same keys before and after and read the TV's own
frame stats.

**Something to watch, the moment Home opens.** A row of chips sits above your apps, and the first one is
already open: new, well-liked films and shows in your language that are streaming on your apps right now, so
every one plays. It can be one person's own chip instead, named for them, mixing their language with, say,
new K-dramas and a few films picked for them. The other chips, each optional:

- **Podcasts**: the newest full episode from each YouTube channel you choose (or YouTube's popular podcasts),
  as cards with the episode's own thumbnail. Episodes about politics, religion or astrology are skipped.
- **Continue** for what you opened last, and **Recently added** to your watchlist: IMDb, Letterboxd or both.
- **Trending in your country**, this week, on the apps you have, and **Trending worldwide**.
- **World cinema**: the best-rated films that aren't in English or your languages.
- **Directors**: a photo for each director you choose; press OK for their best films, Back to return.
- **This year's** most popular and best rated, and your watchlist's genres, each topped up to at least 24 with
  IMDb's best in that genre.

Every time Home is pressed, each row is reshuffled with the titles you didn't see last time in front, so it
never opens the same way twice. Back from an app keeps the order and lands you on the poster you left. A
genre you never want (horror, say) can be left out of every row. Posters are big enough to read from the
sofa, with the IMDb rating in the corner, and the one you're on grows a little. Rest on one for a moment and a
card opens with the year, running time, country, director and plot. Indian films keep the names you know them
by (*Pyaasa*, not "Thirsty").

**The room takes on the film's colours.** Behind the posters, the screen softly washes in the colours of the
title you're on, over a deep plum rather than black. The time sits top left with the date and the weather.
Moving around makes Android TV's own soft sounds, a different one for each direction.

**When the TV rests, it becomes a gallery.** The screensaver hangs one public-domain masterpiece at a time,
two minutes each, on a wash of its own colours: Monet, Van Gogh, Vermeer, Hokusai, Raja Ravi Varma, Amrita
Sher-Gil and more, 64 in all, with the name and painter in the corner and the time and weather beside them.
Nothing moves between paintings, so the TV does almost no work.

**Finding it again is easy.** Open a title and it goes to the front of Continue, so tomorrow it's one press
away. Press Back from the app and you land on the poster you left.

**You never think about which app.** Press OK and the title opens on its own page, in whichever of your apps
has it: Netflix, Prime Video, Hotstar, Apple TV, SonyLIV or Zee5, and Stremio for everything else. The home
screen works out where each title streams in your country and keeps that up to date.

**Less running behind your back.** About twenty packages are switched off: Hisense and Google usage
reporting, the shop demo, the partner app installer, Data Saver (67 MB, always running), a spare keyboard,
and the stock home screen with its recommendation feeds.

**Fewer ads inside apps, if you want it.** One setting sends the TV's lookups through AdGuard's DNS, so
ad and tracking servers stop answering in every app. YouTube ads stay, because they come from the same
servers as the videos.

**It stays this way.** Firmware updates are switched off (this TV's last one was in December 2021). If an
update or a factory reset ever undoes the setup, one command puts it all back.

## What it won't do

- **Cold start still takes about 25 seconds.** That's the firmware. Turn the TV off with the remote rather
  than the wall switch: waking from standby is quick.
- **YouTube ads stay**, for the reason above.
- **Prime Video still asks who's watching** before it opens a title.
- **Hisense TVs running VIDAA** (not Android) can't be changed this way.

## What you need

- A Hisense TV running Android TV. Settings → Device Preferences → About will say so.
- A laptop on the same Wi-Fi (Mac, Windows or Linux).
- [Claude Code](https://claude.com/claude-code) or a similar assistant that can run commands.
- About 30 minutes, most of it the TV restarting while you wait.

## How it works

You say the sentence above. Then:

1. **The assistant finds your TV on the Wi-Fi.** If USB debugging isn't on yet, it walks you through the
   four presses in Settings, one at a time.
2. **It asks you a few questions:** which apps you use, whether anything is plugged in by HDMI, whether you
   keep an IMDb watchlist, and whether to block ads inside apps.
3. **It backs up the TV, then makes the changes in small groups.** The TV restarts after each group and
   gets checked: did it boot, is Home there, are the HDMI inputs still listed. Then the new home screen goes
   on.
4. **It shows you the before and after numbers.**

Want to see what the watchlist row would show before you start? Run
`python3 tools/watchlist-preview.py "<your IMDb watchlist link>"` and it lists where each title streams.

## Safe by design

- **Nothing is deleted from the TV's system.** Packages are switched off, and one command switches them
  back on. Settings → Apps on the TV can do it too.
- **A full backup comes first**: every package, every setting and an undo script.
- **Changes go in small groups**, with a restart and a health check after each: did it boot, is Home there,
  are the HDMI inputs still listed, is the keyboard set.
- **Packages known to break Hisense TVs are never touched**, including the SmartSense family that sent
  another Hisense model into a recovery screen.
- **Your apps' dependencies are kept.** If you sideloaded an app, the app you used to install it stays.
- **The new home screen has no ads, no tracking and no background service.** It only goes online for its
  rows (at most once every 12 hours, while the home screen is open), the weather, posters as they come on
  screen, and the screensaver's next painting.

## Keeping it

After a firmware update or a factory reset, switch USB debugging back on and tell your assistant "put my
TV back", or run:

```
sh tools/reapply.sh           # put back whatever has changed
sh tools/reapply.sh --check   # just show what has changed
```

Everything personal (your TV's address, your backup, your app list, your watchlist settings) lives in
`my-tv/`. It never leaves your laptop, so back it up.

## Tested on

| TV | What was tested |
|---|---|
| Hisense 55U6G (HiSmartTV A4, Android TV 9, India) | Full setup, October 2026 |

Have another Hisense model? The assistant works out the list for your TV, checking each unfamiliar package
before it touches it. Then add your TV to [devices/](devices/) so the next person can skip that step.

## Questions

**Could this break my TV?** Everything can be undone, and nothing is flashed or rooted. The worst case
reported on another Hisense was a recovery screen at start-up. Choosing "Try again" booted it normally, and
the packages behind it are on the never-touch list.

**Will I lose my apps or sign-ins?** No. Your apps, their data and your accounts stay as they are.

**Will my apps still update?** Yes, through the Play Store as before. Stremio is the exception in India,
where it isn't on the Play Store.

**Where does the watchlist come from?** Your public IMDb watchlist, matched against JustWatch's streaming
listings for your country. Both are unofficial data feeds, so this is for personal use. If either changes,
the row keeps showing the last list it saved.

## Credits

The home screen app and the approach come from [cleanyourtv](https://github.com/Gathin23/cleanyourtv) by
Gathin, under the MIT licence. This project adds the Hisense package list, the watchlist row, and the tools
that back up, apply in safe groups and put the setup back after updates.

## Support

It's free, and it stays free. If your TV feels new again and your evenings got a little nicer, you can
[buy me a coffee](https://buymeacoffee.com/youdonthaveto). You don't have to.
