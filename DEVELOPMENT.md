# Developing Muxer

Muxer is a Go relay in `relay/` and an Android app in `app/`. The [README](README.md) covers installing and using it.

## Building and testing

```sh
# relay
cd relay && go test -race ./... && go build ./cmd/muxer

# Android app (JDK 21 and the Android SDK)
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

To run the relay next to an installed copy, give it its own state directory and port:

```sh
cd relay && MUXER_STATE_DIR=/tmp/relay-dev go run ./cmd/muxer serve --listen 127.0.0.1:18765
```

On the Android emulator the computer is `http://10.0.2.2:18765`. Debug builds allow plain HTTP to that address only.

## Releasing

Publish a GitHub release whose tag is a semantic version such as `0.2.0` or `0.2.0-beta.1`, with no `v` prefix. The release workflow tests everything, then attaches the relay for Apple silicon and Intel Macs, the signed APK, and `SHA256SUMS` to that release. The app's version name is the tag, and its version code is `major × 1,000,000 + minor × 1,000 + patch`.

Signing uses the `SIGNING_KEYSTORE_BASE64`, `SIGNING_KEYSTORE_PASSWORD` and `SIGNING_KEY_PASSWORD` secrets and the `SIGNING_KEY_ALIAS` variable.

For releases that are not marked as pre-releases, the workflow also updates the `muxer` formula in [gantoine/homebrew-tap](https://github.com/gantoine/homebrew-tap). It renders the formula with `packaging/homebrew/render-formula.sh` and pushes as a GitHub App that is installed only on the tap with contents write access. The workflow reads the app's ID from the `HOMEBREW_TAP_APP_ID` variable and its private key from the `HOMEBREW_TAP_APP_PRIVATE_KEY` secret, and each token it creates lasts one hour.

After the files are attached, a second job uploads the APK and both relay tarballs to [VirusTotal](https://www.virustotal.com) with `packaging/virustotal/scan.sh`. It waits for the scans to finish and adds a table of detection counts and report links to the end of the release notes. Rerunning the job replaces that table instead of adding a second one. The job reads its API key from the `VT_API_KEY` secret, and a free key is enough because the script waits between requests to stay under the public API rate limit.

## Relay protocol

The phone opens `GET /v1/ws` with `Authorization: Bearer <token>` and exchanges JSON messages.

| From phone | Purpose |
| --- | --- |
| `hello {notifications_after}` | Start the session. The answer carries the computer name and cmux version. |
| `attach {workspace_id, surface_id, fit?}` / `detach` / `resync` | Choose the terminal to stream. |
| `input {text}` / `key {key}` / `paste {text, submit}` / `scroll {lines}` | Send input to the attached terminal. |
| `history {lines?}` | Get up to 5,000 lines above the attached terminal's screen, oldest first, with their own style table. The default is 2,000. |
| `fit {columns, rows}` / `unfit` | Ask cmux to size the terminal for the phone. |
| `create_workspace`, `rename_workspace`, `close_workspace`, `create_terminal` | Manage workspaces. |
| `refresh` | Send the workspace list now instead of at the next poll. |
| `answer_approval {approval_id, mode?, answers?, feedback?}` | Answer an agent's permission request, question or plan. |
| `send_agent_message {text}` | Send up to 32 KB to the agent in the attached terminal as a cmux agent message. |
| `keep_awake` / `set_keep_awake {enabled}` | Read or change whether cmux keeps the computer awake. |

The relay sends `workspaces` (with the workspace `groups`, each naming its anchor workspace and members), `screen` (row diffs with a style table), `detached`, `notification` and `result {id, ok, data | error}`. It also sends `approvals` with the agent requests waiting for an answer, `agent_activity {retries, busy}`, `keep_awake {enabled}`, and `agent_messages {surface_id, messages}` for the attached terminal. For the attached terminal it also sends `chat_session {surface_id, session_id}`, with `session_id` null when no Claude Code session runs there, and `chat {surface_id, session_id, reset, entries}`, where each entry is a user message, an assistant reply, a tool call or a notice. `reset` replaces the conversation; otherwise entries with a known `id` replace the old ones and new ones are added at the end. Add an `id` to any message to get a `result` back.
