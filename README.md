# cmux for Android

Watch and drive your [cmux](https://github.com/manaflow-ai/cmux) terminals from an Android phone. See every workspace, open a live terminal, type into it, and get notified when a coding agent needs you.

It works with any cmux plan, and you don't need to sign in to cmux. Everything runs between your phone and your own Mac over [Tailscale](https://tailscale.com). There is no cloud service in the middle.

> This is an independent project. It is not made, endorsed, or supported by Manaflow or the cmux team.

## How it works

```
Android app ──HTTPS + WebSocket over Tailscale──▶ cmux-relay (on your Mac) ──Unix socket──▶ cmux
```

`cmux-relay` is a small program that runs on your Mac. It talks to cmux through cmux's local control socket and gives the phone:

- the list of windows, workspaces and terminals, with agent status and unread counts,
- a live view of one terminal, sent as changed rows only, and the keys you type,
- cmux notifications (for example "Claude is waiting for your input"), with their text.

The phone only reaches the relay through `tailscale serve`, which gives it HTTPS on your private tailnet. The relay itself listens on `127.0.0.1`.

## Set up your Mac

You need cmux 0.65 or later, Go 1.26 or later (until release binaries are published), and Tailscale signed in on the Mac and on the phone.

1. **Install the relay.**

   ```sh
   go install github.com/gantoine/cmux-android/relay/cmd/cmux-relay@latest
   ```

2. **Let the relay talk to cmux.** By default cmux only accepts programs started inside cmux terminals. The relay runs in the background, so switch the socket mode to **password** (recommended) or **automation**:
   - In cmux, open **Settings › Automation** and set socket access to **Password**, then set a password. The relay reads it from `~/.local/state/cmux/socket-control-password`, the same file the cmux CLI uses.
   - Or set `"automation": { "socketControlMode": "automation" }` in `~/.config/cmux/cmux.json`. This lets any program running as your user control cmux without a password.

3. **Start the relay at login.**

   ```sh
   cmux-relay install
   ```

4. **Make it reachable from your tailnet only.**

   ```sh
   tailscale serve --bg --https=8765 http://127.0.0.1:8765
   ```

5. **Check everything.**

   ```sh
   cmux-relay doctor
   ```

## Pair your phone

Install the APK from the [releases page](../../releases), then run this on your Mac:

```sh
cmux-relay pair
```

Scan the QR code with your phone's camera, or tap **Pair a Mac** in the app and type the address and code. The code works once and expires after 10 minutes.

Pair as many Macs as you like. Each one shows up in the app's Mac list.

## Using the app

- **Workspaces:** tap a workspace to open its terminal. Long-press to rename it, add a terminal, or close it. Tap **+** to create a workspace.
- **Terminal:** pinch to zoom, drag sideways to pan, drag up and down to scroll the Mac's scrollback. Tap the terminal to bring up the keyboard.
- **Typing:** by default you type a line and press send. Turn on **live typing** (keyboard icon) to send every key as you type, which works better in full-screen programs. The key bar has Esc, Tab, a sticky Ctrl, arrows, ^C, ^D, backspace, Enter, and paste.
- **Fit to phone:** the fit icon resizes the Mac terminal to the phone's screen while you watch. cmux gives the size back when you leave the terminal.
- **Background notifications:** turn on **Notify me in the background** on the Mac list. The app then keeps a connection open in a foreground service, so cmux notifications reach you while the app is closed. Reading a notification on the Mac removes it from the phone.

## Managing phones

```sh
cmux-relay devices          # list paired phones
cmux-relay revoke DEVICE_ID # disconnect a phone and make it pair again
cmux-relay uninstall        # stop the relay and remove it from login items
```

The relay logs to `~/Library/Logs/cmux-relay.log`.

## Security

- The phone can do what you can do in cmux: read every terminal and type into it. Pair only phones you control, and revoke lost ones.
- Each phone gets its own random token. The Mac stores only a SHA-256 hash of it, in `~/Library/Application Support/cmux-android-relay/devices.json` (mode 0600). The phone keeps it encrypted with a key in the Android Keystore.
- Pairing codes are single-use, expire after 10 minutes, and stop working after 10 wrong guesses.
- The relay listens on `127.0.0.1` only. `tailscale serve` adds TLS and keeps it inside your tailnet. Never expose it with `tailscale funnel` or a public port.
- Pairing, listing, and revoking use a Unix socket that only your user can open, never the network.
- The relay only forwards the commands listed in the protocol below. It is not a general cmux socket proxy.

## Limits

- The relay uses cmux's `mobile.*` socket methods, which power the official iOS app. cmux does not document them, so a cmux update can break the relay. `cmux-relay doctor` and the relay log show what failed.
- cmux has no way to push terminal output over its local socket, so the relay polls the terminal you are viewing: about 12 times a second while it changes, slowing to once a second when idle. It polls only while a phone has a terminal open.
- Background notifications need the foreground service. Without it, notifications that arrive while the app is closed are delivered the next time you open it.

## Development

```sh
# relay
cd relay && go test -race ./... && go build ./cmd/cmux-relay

# Android app (JDK 21 and the Android SDK)
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

To run the relay next to an installed copy, give it its own state directory and port:

```sh
CMUX_RELAY_STATE_DIR=/tmp/relay-dev go run ./relay/cmd/cmux-relay serve --listen 127.0.0.1:18765
```

On the Android emulator the Mac is `http://10.0.2.2:18765`. Debug builds allow plain HTTP to that address only.

### Relay protocol

The phone opens `GET /v1/ws` with `Authorization: Bearer <token>` and exchanges JSON messages.

| From phone | Purpose |
| --- | --- |
| `hello {notifications_after}` | Start the session. The answer carries the Mac name and cmux version. |
| `attach {workspace_id, surface_id, fit?}` / `detach` / `resync` | Choose the terminal to stream. |
| `input {text}` / `key {key}` / `paste {text, submit}` / `scroll {lines}` | Send input to the attached terminal. |
| `fit {columns, rows}` / `unfit` | Ask cmux to size the terminal for the phone. |
| `create_workspace`, `rename_workspace`, `close_workspace`, `create_terminal` | Manage workspaces. |

The relay sends `workspaces`, `screen` (row diffs with a style table), `detached`, `notification` and `result {id, ok, data | error}`. Add an `id` to any message to get a `result` back.

## License

MIT. See [LICENSE](LICENSE).
