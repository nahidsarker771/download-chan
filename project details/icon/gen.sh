#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

ROOT="$HOME/Downloads/download-chan"
RES="$ROOT/project files/downloadchan/android/app/src/main/res"
WEB="$ROOT/project files/downloadchan/www"
FONT_BOLD="/usr/share/fonts/liberation/LiberationSans-Bold.ttf"
FONT_REG="/usr/share/fonts/liberation/LiberationSans-Regular.ttf"

# --- rasterize masters at 1024 ---
rsvg-convert -w 1024 -h 1024 art.svg -o art.png
rsvg-convert -w 1024 -h 1024 bg.svg  -o bg.png

# --- square icon (chubby cat over indigo gradient) ---
magick bg.png art.png -composite square.png

# --- round icon (circular mask) ---
magick -size 1024x1024 xc:none -fill white -draw "circle 512,512 512,24" mask.png
magick square.png mask.png -alpha off -compose CopyOpacity -composite round.png

# --- adaptive foreground: art scaled to ~66% centered on transparent 1024 ---
magick art.png -resize 683x683 art_small.png
magick -size 1024x1024 xc:none art_small.png -gravity center -composite fg.png

# --- emit densities ---
# legacy launcher: mdpi48 hdpi72 xhdpi96 xxhdpi144 xxxhdpi192
# adaptive fg (108dp): mdpi108 hdpi162 xhdpi216 xxhdpi324 xxxhdpi432
emit() { # src dp_px size dir prefix
  magick "$1" -resize "$3"x"$3" "$RES/$4/$5.png"
}
for spec in "mdpi 48 108" "hdpi 72 162" "xhdpi 96 216" "xxhdpi 144 324" "xxxhdpi 192 432"; do
  set -- $spec
  d="$1"; leg="$2"; fg="$3"
  emit square.png x "$leg" "mipmap-$d" ic_launcher
  emit round.png  x "$leg" "mipmap-$d" ic_launcher_round
  emit fg.png     x "$fg"  "mipmap-$d" ic_launcher_foreground
done

# --- in-app header mark ---
mkdir -p "$WEB/img"
magick square.png -resize 144x144 "$WEB/img/logo.png"

# --- splash: soft indigo gradient + floating mascot + wordmark ---
make_splash() { # width height output
  local w="$1" h="$2" out="$3"
  local min=$(( w < h ? w : h ))
  local icon=$(( min * 44 / 100 ))
  local title=$(( min * 62 / 1000 ))
  local sub=$(( min * 30 / 1000 ))
  local icon_dy=$(( min * 5 / 100 ))
  local title_dy=$(( min * 23 / 100 ))
  local sub_dy=$(( min * 31 / 100 ))

  magick -size "${w}x${h}" gradient:'#14152a-#2b2350' \
    \( -size "${w}x${h}" radial-gradient:'#6c5df566-#00000000' \) -compose over -composite \
    \( -size "${w}x${h}" radial-gradient:'#ffcf9b30-#00000000' \) -compose over -composite \
    \( art.png -resize "${icon}x${icon}" \) -gravity center \
       -geometry "+0-${icon_dy}" -compose over -composite \
    -font "$FONT_BOLD" -pointsize "$title" -fill '#f7f4ff' \
    -gravity center -annotate "+0+${title_dy}" 'Download Chan' \
    -font "$FONT_REG" -pointsize "$sub" -fill '#b8b0dd' \
    -annotate "+0+${sub_dy}" 'save anything' \
    -depth 8 -strip -define png:compression-level=9 "$out"
}

make_splash 480 320  "$RES/drawable/splash.png"
make_splash 480 320  "$RES/drawable-land-mdpi/splash.png"
make_splash 800 480  "$RES/drawable-land-hdpi/splash.png"
make_splash 1280 720 "$RES/drawable-land-xhdpi/splash.png"
make_splash 1600 960 "$RES/drawable-land-xxhdpi/splash.png"
make_splash 1920 1280 "$RES/drawable-land-xxxhdpi/splash.png"
make_splash 320 480  "$RES/drawable-port-mdpi/splash.png"
make_splash 480 800  "$RES/drawable-port-hdpi/splash.png"
make_splash 720 1280 "$RES/drawable-port-xhdpi/splash.png"
make_splash 960 1600 "$RES/drawable-port-xxhdpi/splash.png"
make_splash 1280 1920 "$RES/drawable-port-xxxhdpi/splash.png"

echo "generated:"; ls -1 "$RES"/mipmap-mdpi
identify "$RES/mipmap-xxxhdpi/ic_launcher.png" "$RES/mipmap-xxxhdpi/ic_launcher_foreground.png" "$RES/drawable-port-xxxhdpi/splash.png" 2>/dev/null || true
