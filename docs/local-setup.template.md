# Local setup values — template

`AGENTS.md`, `ARCHITECTURE.md` and `PROGRESS.md` use `<TOKENS>` instead of real
hostnames, IPs, usernames and domains so this repo can go public without
leaking anyone's setup. Tokens describe each machine's *role*, not its name.

Copy this file to `local/setup-values.md` (gitignored — never commit the real
values) and fill in your own. Agents: never guess these; ask the user for any
setup value you need.

| Token           | Meaning                                                                 | Your value |
|-----------------|-------------------------------------------------------------------------|------------|
| `<QUEST>`       | Quest headset (adb install target)                                      |            |
| `<MAC>`         | Mac test host (VNC / Screen Sharing)                                    |            |
| `<LAPTOP>`      | Windows laptop (Windows Home + RDP Wrapper; no camera/mic) — used for RDP testing where camera/mic are unavailable | |
| `<CAM_HOST>`    | Windows host with camera + mic; supports multiple concurrent sessions — used for camera/mic/multi-session testing | |
| `<JUMP_TARGET>` | Remote SSH jump-test target (ubuntu, via bastion)                       |            |
| `<BASTION>`     | SSH jump-test bastion, first hop (hostname or IP — a Cloudflare hostname works too, but no domain is required) | |
| `<USER>`        | Your username / account names                                           |            |
| `<USER_DOMAIN>` | Your domain, if you have one (used in Cloudflare hostnames, e.g. `ssh.<USER_DOMAIN>`); plain hostnames/IPs work anywhere a domain is shown |            |

Notes:
- `<CAM_HOST> 2` in historical notes = a second camera-capable Windows host.
- The jump-test bastion is `<BASTION>` (first hop); `<JUMP_TARGET>`
  is the second hop behind it.
- Historical `PROGRESS.md` entries may reference these tokens in old adb-install
  `IP:port` strings and evidence paths — the ports were ephemeral.
