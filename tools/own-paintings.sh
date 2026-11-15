#!/bin/sh
# What the screensaver hangs: the masters alone, a mix with the household's own paintings ("screensaver_own" in
# config.json), or only their own. The same setting Left/Right changes on the screensaver itself.
#   tools/own-paintings.sh mix | own | masters | status      (on = mix, only = own, off = masters)
. "$(dirname "$0")/lib.sh"
case "${1:-status}" in
  on|mix) mode=mix ;;
  only|own) mode=own ;;
  off|masters) mode=masters ;;
  status) mode=status ;;
  *) echo "usage: own-paintings.sh mix | own | masters | status" >&2; exit 2 ;;
esac
connect
now=$(tv am broadcast -n "$LAUNCHER/.GalleryModeReceiver" -a com.gathin.tvhome.GALLERY_MODE --es mode "$mode" |
  sed -n 's/.*data="\([a-z]*\)".*/\1/p')
case "$now" in
  masters) echo "Screensaver: the masters only." ;;
  mix) echo "Screensaver: her paintings mixed in, one after every two masters." ;;
  own) echo "Screensaver: only her paintings." ;;
  *) echo "The TV didn't answer. Is the home screen app up to date (tools/install-home.sh)?" >&2; exit 1 ;;
esac
