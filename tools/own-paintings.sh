#!/bin/sh
# Turns the household's own paintings ("screensaver_own" in config.json) on or off in the screensaver: say, on
# when guests are coming, off again after. They hang one after every two masterpieces while on.
#   tools/own-paintings.sh on | off | status
. "$(dirname "$0")/lib.sh"
want=${1:-status}
case "$want" in on|off|status) ;; *) echo "usage: own-paintings.sh on | off | status" >&2; exit 2 ;; esac
[ -f "$MY/config.json" ] || { echo "No $MY/config.json" >&2; exit 1; }
python3 - "$MY/config.json" "$want" <<'PY' || exit 1
import json, sys
path, want = sys.argv[1], sys.argv[2]
cfg = json.load(open(path))
n = len(cfg.get("screensaver_own") or [])
if want != "status":
    cfg["screensaver_own_on"] = want == "on"
    json.dump(cfg, open(path, "w"), indent=2, ensure_ascii=False)
print(f"Her paintings in the screensaver: {'on' if cfg.get('screensaver_own_on') else 'off'} ({n} paintings listed)")
PY
[ "$want" = status ] && exit 0
connect
push_config "$MY/config.json" && echo "Sent to the TV. It takes effect the next time the screensaver starts."
