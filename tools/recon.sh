#!/usr/bin/env bash
#
# recon.sh - confirm the patch anchors from docs/02-instagram-apk.md exist in a
# given Instagram APK and print the current names of the things that move.
#
#   tools/recon.sh instagram.apk [outdir]
#
# Needs: apktool (https://apktool.org), ripgrep or grep, unzip, aapt2 (optional,
# for resource ids). Decoding Instagram takes several minutes and ~3 GB.
set -euo pipefail

APK="${1:-}"
OUT="${2:-build/recon}"
if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "usage: tools/recon.sh <instagram.apk> [outdir]" >&2
  exit 1
fi

GREP="grep -r"
command -v rg >/dev/null 2>&1 && GREP="rg --no-heading -N"

mkdir -p "$OUT"
echo "==> package / version"
if command -v aapt2 >/dev/null 2>&1; then
  aapt2 dump badging "$APK" | grep -E "^package:" || true
else
  unzip -p "$APK" AndroidManifest.xml | strings | grep -E "^[0-9]+\.[0-9]+\.[0-9]+" | head -2 || true
fi

echo "==> dex count and native libs"
unzip -l "$APK" | awk '{print $4}' | grep -E "^classes[0-9]*\.dex$" | wc -l
unzip -l "$APK" | awk '{print $4}' | grep -E "^lib/" | sed 's#lib/\([^/]*\)/.*#\1#' | sort -u

if [ ! -d "$OUT/smali" ] && [ ! -d "$OUT/smali_classes2" ]; then
  echo "==> apktool decode (no resources) -> $OUT"
  apktool d -f --no-res -o "$OUT" "$APK" >/dev/null
fi

echo
echo "==> named anchors (must exist)"
for cls in \
  "com/instagram/api/tigon/TigonServiceLayer" \
  "com/instagram/mainactivity/InstagramMainActivity" \
  "com/instagram/common/session/UserSession"; do
  f=$(find "$OUT" -path "*/$cls.smali" | head -1 || true)
  if [ -n "$f" ]; then echo "  OK   $cls  ($f)"; else echo "  MISS $cls"; fi
done
f=$(find "$OUT" -path "*/com/instagram/api/tigon/TigonServiceLayer.smali" | head -1 || true)
if [ -n "$f" ]; then
  echo "  startRequest signature(s):"
  grep -E "^\.method .*startRequest" "$f" | sed 's/^/    /'
  echo "  java.net.URI field loads inside it:"
  grep -c "Ljava/net/URI;" "$f" | sed 's/^/    count=/'
fi

echo
echo "==> string anchors"
for s in \
  "Invalid SHA256 key hash" \
  "is_employee" \
  "clips_netego" \
  "parseFromJson" \
  "ClipsOrganicMediaItemViewMoreOptionsController" \
  "ig_disable_video_autoplay"; do
  n=$($GREP -l --include='*.smali' -F "\"$s\"" "$OUT" 2>/dev/null | wc -l || true)
  printf "  %-50s in %s file(s)\n" "\"$s\"" "$n"
done

echo
echo "==> clips viewer config / entry-point candidates"
find "$OUT" -iname "*ClipsViewer*Config*.smali" -o -iname "*ClipsViewerSource*.smali" 2>/dev/null | sed 's/^/  /' | head -20
echo "  classes mentioning clips_viewer + writeToParcel:"
$GREP -l --include='*.smali' -F "writeToParcel" "$OUT" 2>/dev/null | xargs -r grep -l -i "clips" 2>/dev/null | head -10 | sed 's/^/  /'

echo
echo "==> clips endpoints referenced in code (for the Gate decision table)"
$GREP -o --include='*.smali' -h -E '"[a-z0-9_/]*clips/[a-z0-9_/]*"' "$OUT" 2>/dev/null | sort | uniq -c | sort -rn | head -60 | sed 's/^/  /'

echo
echo "==> direct_v2 media/clip endpoints (must stay allowed)"
$GREP -o --include='*.smali' -h -E '"direct_v2/[a-z0-9_/]*(clip|media|reel)[a-z0-9_/]*"' "$OUT" 2>/dev/null | sort -u | head -20 | sed 's/^/  /'

echo
echo "==> resource ids used by the UI patches (present in the resource table?)"
if command -v aapt2 >/dev/null 2>&1; then
  aapt2 dump resources "$APK" > "$OUT/resources.txt" 2>/dev/null || true
  for id in feed_tab search_tab clips_tab creation_tab direct_tab profile_tab tab_bar \
            swipeable_tab_view_pager clips_viewer_action_bar action_bar_tab_layout \
            clips_video_container clips_video_player clips_play_button; do
    if grep -q "id/$id" "$OUT/resources.txt"; then echo "  OK   id/$id"; else echo "  MISS id/$id"; fi
  done
else
  echo "  (aapt2 not on PATH; install Android build-tools to check resource ids)"
fi

echo
echo "==> clips viewer source enum strings (helps ReelContext)"
$GREP -o --include='*.smali' -h -E '"(clips_tab|direct_thread|direct|feed_timeline|explore_[a-z_]*|profile_clips|clips_[a-z_]*viewer[a-z_]*)"' "$OUT" 2>/dev/null | sort | uniq -c | sort -rn | head -30 | sed 's/^/  /'

echo
echo "done. Decoded tree kept in $OUT for manual digging (jadx-gui on the APK is faster for reading)."
