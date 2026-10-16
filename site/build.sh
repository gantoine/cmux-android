#!/bin/sh
set -eu

site_dir=$(cd "$(dirname "$0")" && pwd)
repo_dir=$(dirname "$site_dir")
out_dir="$site_dir/dist"
site_url=${SITE_URL:-https://muxer.gantoine.com}
site_url=${site_url%/}

rm -rf "$out_dir"
mkdir -p "$out_dir/assets"

sed "s|{{SITE_URL}}|$site_url|g" "$site_dir/index.html" > "$out_dir/index.html"
cp "$site_dir/style.css" "$out_dir/"
cp "$repo_dir/assets/logo.svg" "$repo_dir/assets/logo.png" \
   "$repo_dir/assets/screenshot-computers.png" "$repo_dir/assets/screenshot-workspaces.png" \
   "$repo_dir/assets/screenshot-chat.png" "$repo_dir/assets/screenshot-terminal.png" \
   "$repo_dir/assets/social-preview.png" "$repo_dir/assets/cmux-logo.png" \
   "$out_dir/assets/"

echo "Built $out_dir for $site_url"
