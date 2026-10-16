#!/usr/bin/env bash
set -euo pipefail

if [ $# -lt 1 ] || [ $# -gt 2 ] || { [ $# -eq 2 ] && [ "$2" != "--prerelease" ]; }; then
  echo "Usage: $0 <version> [--prerelease]" >&2
  exit 1
fi

version=$1
prerelease=${2:-}
readme="$(dirname "$0")/../../README.md"

fingerprint=$(grep -oE '([0-9A-F]{2}:){31}[0-9A-F]{2}' "$readme" | head -n 1)
if [ -z "$fingerprint" ]; then
  echo "No SHA-256 certificate fingerprint found in README.md." >&2
  exit 1
fi

echo '<details>'
echo '<summary>How to update</summary>'
echo
echo '**On your computer**'
echo
if [ -n "$prerelease" ]; then
  cat <<EOF
Homebrew only ships full releases. Download \`muxer-${version}-darwin-arm64.tar.gz\` (Apple silicon) or \`muxer-${version}-darwin-amd64.tar.gz\` (Intel) from the assets below, check it against \`SHA256SUMS\`, extract it over your current \`muxer\`, then run:

\`\`\`sh
muxer install
\`\`\`
EOF
else
  cat <<'EOF'
```sh
brew upgrade muxer
muxer install
```
EOF
fi
cat <<EOF

Paired phones stay paired.

**On your phone**

Install \`muxer-${version}.apk\` from the assets below over the previous version.

Check the APK's signing certificate before installing: \`apksigner verify --print-certs muxer-${version}.apk\` should show SHA-256 \`${fingerprint}\`.

New here? Follow the [README](https://github.com/gantoine/muxer#set-up-your-computer) to set up your computer and pair your phone.

</details>
EOF
