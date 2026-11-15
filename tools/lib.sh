# Shared by the tools. Your TV's details live in my-tv/tv.env (never committed):
#   TV_HOST=192.168.1.50
# Everything personal (backups, your debloat list, config.json, extra APKs) lives in my-tv/ too.

REPO=${REPO:-$(cd "$(dirname "$0")/.." && pwd)}   # scripts outside tools/ set REPO first
MY="${MY:-$REPO/my-tv}"                            # MY=<folder> for a second TV
[ -f "$MY/tv.env" ] && . "$MY/tv.env"
TV="${TV_HOST:?set TV_HOST in my-tv/tv.env, e.g. TV_HOST=192.168.1.50}:5555"
LAUNCHER=com.gathin.tvhome

# One shell command on the TV. -n: never read the caller's stdin, or a while-read loop loses its lines.
tv() { adb -s "$TV" shell -n "$@" | tr -d '\r'; }

# Connects and waits for the TV to accept this computer. Exits if it cannot.
connect() {
  adb connect "$TV" >/dev/null 2>&1
  state=""
  for _ in 1 2 3 4 5 6 7 8 9 10 11 12; do
    state=$(adb devices | grep "^$TV" | awk '{print $2}')
    [ "$state" = "device" ] && return 0
    [ "$state" = "unauthorized" ] && echo "  On the TV: accept 'Allow USB debugging?' and tick Always allow."
    sleep 5
    adb connect "$TV" >/dev/null 2>&1
  done
  echo "Cannot reach the TV at $TV ($state). Is it on, on the same Wi-Fi, with USB debugging on?" >&2
  exit 1
}

# Package names from a debloat list: one per line, # comments allowed.
list_packages() { grep -vE '^[[:space:]]*(#|$)' "$1" | sed -E 's/[[:space:]]+#.*$//' | awk '{print $1}'; }

# config.json goes in the home screen's Android/data folder, or its Android/media folder where adb can't reach
# Android/data (Fire OS). The home screen reads either.
CONFIG_DATA=/sdcard/Android/data/$LAUNCHER/files/config.json
CONFIG_MEDIA=/sdcard/Android/media/$LAUNCHER/config.json
config_on_tv() { tv "cat $CONFIG_DATA 2>/dev/null || cat $CONFIG_MEDIA 2>/dev/null"; }
# Pushes a config.json and restarts the home screen so it reads it.
push_config() {
  adb -s "$TV" push "$1" "$CONFIG_DATA" >/dev/null 2>&1 ||
    { tv mkdir -p "${CONFIG_MEDIA%/*}" && adb -s "$TV" push "$1" "$CONFIG_MEDIA" >/dev/null 2>&1; } || return 1
  tv am force-stop $LAUNCHER
  # Fire OS switches an app's accessibility service off when the app is force-stopped: put the Home button back.
  if [ "$(home_activity | cut -d/ -f1)" = "$FIRE_HOME" ]; then enable_home_service; fi
  return 0
}

# Fire OS keeps Amazon's home as the default and can't be changed or switched off. HomeKeyService (an
# accessibility service in the home screen app) opens the new home whenever Amazon's comes up.
FIRE_HOME=com.amazon.tv.launcher
HOME_SERVICE=$LAUNCHER/.HomeKeyService
home_service_on() {
  case "$(tv settings get secure enabled_accessibility_services)" in
    *"$HOME_SERVICE"*) [ "$(tv settings get secure accessibility_enabled)" = 1 ] ;;
    *) return 1 ;;
  esac
}
enable_home_service() {
  cur=$(tv settings get secure enabled_accessibility_services)
  case "$cur" in
    *"$HOME_SERVICE"*) ;;
    ""|null) tv settings put secure enabled_accessibility_services "$HOME_SERVICE" ;;
    *) tv settings put secure enabled_accessibility_services "$cur:$HOME_SERVICE" ;;
  esac
  tv settings put secure accessibility_enabled 1
}

# Fire OS: installs an app from the Amazon Appstore by opening its page and pressing Get, then waits for it.
appstore_install() {
  tv am start -a android.intent.action.VIEW -d "amzn://apps/android?p=$1" >/dev/null
  sleep 8
  tv input keyevent DPAD_CENTER
  n=0
  while [ $n -lt 36 ]; do
    sleep 5
    if tv pm list packages "$1" | grep -qx "package:$1"; then tv input keyevent KEYCODE_HOME; return 0; fi
    n=$((n + 1))
  done
  tv input keyevent KEYCODE_HOME
  return 1
}

home_activity() { tv cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -n 1; }
