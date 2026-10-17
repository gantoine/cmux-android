#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
recording="${RECORDING:-$root/relay/e2e/testdata/recording.json}"
relay_port="${RELAY_PORT:-8771}"
control_port="${CONTROL_PORT:-8790}"
work="$(mktemp -d /tmp/muxer-ui.XXXXXX)"
pids=()

cleanup() {
  for pid in "${pids[@]}"; do kill "$pid" 2>/dev/null || true; done
  rm -rf "$work"
}
trap cleanup EXIT

(cd "$root/relay" && go build -o "$work/muxer" ./cmd/muxer && go build -o "$work/cmux-replay" ./cmd/cmux-replay)

"$work/cmux-replay" -recording "$recording" -socket "$work/cmux.sock" -control "127.0.0.1:$control_port" > "$work/replay.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 50); do [ -S "$work/cmux.sock" ] && break; sleep 0.1; done

export CMUX_SOCKET_PATH="$work/cmux.sock" CMUX_SOCKET_PASSWORD=replay MUXER_STATE_DIR="$work/state"
"$work/muxer" serve --listen "127.0.0.1:$relay_port" --name "Replay Mac" > "$work/relay.log" 2>&1 &
pids+=($!)
for _ in $(seq 1 100); do curl -fsS "http://127.0.0.1:$relay_port/v1/health" >/dev/null 2>&1 && break; sleep 0.1; done

"$work/muxer" pair --listen "127.0.0.1:$relay_port" --url "http://127.0.0.1:$relay_port" > "$work/pair.txt" 2>&1 &
pids+=($!)
for _ in $(seq 1 50); do grep -q 'Code:' "$work/pair.txt" && break; sleep 0.1; done
code="$(awk '/Code:/ {print $2}' "$work/pair.txt")"
token="$(curl -fsS -X POST "http://127.0.0.1:$relay_port/v1/pair" -d "{\"code\":\"$code\",\"device_name\":\"ui tests\"}" | sed -E 's/.*"token":"([^"]+)".*/\1/')"

adb shell rm -rf /data/local/tmp/ui-test-failures >/dev/null 2>&1 || true
status=0
(cd "$root" && ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.relayUrl="http://10.0.2.2:$relay_port" \
  -Pandroid.testInstrumentationRunnerArguments.controlUrl="http://10.0.2.2:$control_port" \
  -Pandroid.testInstrumentationRunnerArguments.relayToken="$token") || status=$?

if [ "$status" -ne 0 ]; then
  echo "--- relay log ---"; cat "$work/relay.log"
  echo "--- replay log ---"; cat "$work/replay.log"
  failures="$root/app/build/reports/androidTests/failures"
  mkdir -p "$failures"
  adb pull /data/local/tmp/ui-test-failures/. "$failures" >/dev/null 2>&1 || true
fi
exit "$status"
