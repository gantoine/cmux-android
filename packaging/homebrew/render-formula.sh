#!/bin/bash
set -euo pipefail

if [ $# -lt 3 ]; then
  echo "usage: $0 VERSION ARM64_SHA256 AMD64_SHA256 [DOWNLOAD_BASE_URL]" >&2
  exit 2
fi

version=$1
arm64_sha=$2
amd64_sha=$3
base_url=${4:-https://github.com/gantoine/muxer/releases/download/$version}

cat <<FORMULA
class Muxer < Formula
  desc "Connects the Muxer Android app to cmux on this Mac"
  homepage "https://github.com/gantoine/muxer"
  version "$version"
  license "MIT"

  if Hardware::CPU.arm?
    url "$base_url/muxer-$version-darwin-arm64.tar.gz"
    sha256 "$arm64_sha"
  else
    url "$base_url/muxer-$version-darwin-amd64.tar.gz"
    sha256 "$amd64_sha"
  end

  depends_on :macos

  def install
    bin.install "muxer"
  end

  def caveats
    <<~EOS
      Set up the relay:
        muxer install
        tailscale serve --bg --https=8765 http://127.0.0.1:8765   # or another HTTPS proxy, see the README
        muxer doctor
        muxer pair

      After upgrading, run \`muxer install\` again to restart the relay on the new version.
    EOS
  end

  test do
    assert_equal version.to_s, shell_output("#{bin}/muxer version").strip
  end
end
FORMULA
