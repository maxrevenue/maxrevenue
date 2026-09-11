# Roatz

A Java Instrumentation agent that attaches to the Roat PKz client and drives
PK combat automation, plus a branded Windows launcher with revocable license
keys. The agent still lives under `com.sun.java.fontmgr` and supports both
load-time (`-javaagent`) and runtime (Dynamic Attach) injection.

> **Disclaimer:** This tool automates game play in violation of Roat PKz's
> terms of service. It is published as a reverse-engineering and JVM
> instrumentation exercise only. Use of the "stealth" layer (agent token
> scrubbing, `AhkDetection` neutralization, imitation cache paths) is
> detection-avoidance and carries account-ban risk. Packaging a launcher and
> license keys does not make this an official client. **Do not ship Roat,
> Jagex, or RuneLite binaries** — buyers install Roat PKz themselves.

## Public launcher (buyers)

Buyers never run Gradle or PowerShell. You cut a Windows installer; they
activate a key, start their existing Roat install, log in, then attach.

```powershell
.\gradlew.bat dist "-ProatzLicenseApi=https://roatz-license.<account>.workers.dev"
# -> build\dist\Roatz-Setup.exe  (needs Inno Setup 6 + a JDK with jpackage)
# App image only (no Inno): build\jpackage\Roatz\Roatz.exe
```

Buyer flow: run **Roatz** → paste `RZ-XXXX-XXXX-XXXX` → **Activate** →
**Start** (launches their own vanilla client) → log in → **Attach**. The signed
token is cached at `%APPDATA%\Roatz\license.dat` for 72h, so a Worker outage
does not lock out an already-activated buyer.

---

## License server: production runbook

Currently deployed Worker (`license-server/`):

| | |
|---|---|
| URL | `https://roatz-license.alec-5c7.workers.dev` |
| Worker name | `roatz-license` |
| KV binding | `LICENSES` → `roatz-license-licenses` (`c9073d13ebe4413a8fa832bd2ed31cf0`) |
| Secrets | `TOKEN_SECRET`, `ADMIN_SECRET` |

The `LICENSES` binding is pinned to the namespace id in `wrangler.jsonc`, so
redeploys resolve the same KV store instead of provisioning a new one. (The id
was auto-provisioned by the first deploy and is now committed.)

### 1. Deploy (one time)

```powershell
cd license-server
npm install
npx wrangler login

# TOKEN_SECRET must equal LicenseToken.HMAC_SECRET
# (src\com\sun\java\fontmgr\LicenseToken.java). If they differ, activation
# succeeds but every token fails verification in the client.
'<paste HMAC_SECRET>' | npx wrangler secret put TOKEN_SECRET

# ADMIN_SECRET guards /v1/issue and /v1/revoke. Generate a strong one, keep it
# in a password manager, and never ship it to buyers.
node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))" | npx wrangler secret put ADMIN_SECRET

npx wrangler deploy      # prints https://roatz-license.<account>.workers.dev
npx wrangler secret list
```

Rotating `TOKEN_SECRET` is just another `secret put`. Tokens already cached by
buyers stay valid until their 72h TTL expires, after which `/v1/check` re-issues
from the new secret. No installer rebuild is needed for secret rotation.

### 2. Verify the live Worker

```powershell
Invoke-RestMethod https://roatz-license.alec-5c7.workers.dev/v1/health
# ok   name
# ---- -----
# True roatz-license
```

Expected `POST /v1/activate` answers for a bad key or wrong machine:
`401 invalid`, `403 revoked`, `409 other_pc`.

### 3. Issue and revoke

```powershell
$env:ROATZ_LICENSE_API  = "https://roatz-license.alec-5c7.workers.dev"
$env:ROATZ_ADMIN_SECRET = "<ADMIN_SECRET>"

.\scripts\issue-key.ps1 -Note "buyer@example"     # -> RZ-XXXX-XXXX-XXXX
.\scripts\revoke-key.ps1 RZ-XXXX-XXXX-XXXX
```

A key binds to one machine's HWID on first activate and revoking is immediate:
the KV record is overwritten with `status: "revoked"`. A replacement key is a
*new* KV record — the old key keeps its HWID binding, so a legitimate hardware
change always means issuing fresh.

### 4. Rebuild the installer after the API URL changes

`-ProatzLicenseApi` is baked into the app image as `-Droatz.license.api=<url>`,
so changing the URL **requires a new `Roatz-Setup.exe`**:

```powershell
.\gradlew.bat dist "-ProatzLicenseApi=https://<new-url>"
Select-String -Path build\jpackage\Roatz\app\Roatz.cfg -Pattern 'license.api'
```

Resolution order (`LicenseClient.apiBase()`):

`-Droatz.license.api` (baked into `Roatz.cfg`) → `ROATZ_LICENSE_API` env →
`%APPDATA%\Roatz\config.properties` (`license.api=`) → `Product.LICENSE_API_DEFAULT`.

Since the baked `-D` wins, repointing an **already installed** copy without a
rebuild means editing the `java-options=-Droatz.license.api=` line in
`%LOCALAPPDATA%\Roatz\app\Roatz.cfg` and restarting the launcher.

> Pass `-ProatzLicenseApi` when cutting a release. Omitting it falls back to
> `Product.LICENSE_API_DEFAULT`, which is kept in sync with the Worker above, so
> the only effect is that a differently-targeted deployment would be missed.

### 5. Local development

```powershell
cd license-server
copy .dev.vars.example .dev.vars    # edit ADMIN_SECRET + TOKEN_SECRET
npx wrangler dev --port 8787
# other terminal:
$env:ROATZ_LICENSE_API  = "http://127.0.0.1:8787"
$env:ROATZ_ADMIN_SECRET = "change-me-local-admin"
.\gradlew.bat runLauncher
```

`.\launch.ps1` remains the **dev** loop and sets `-Dfontmgr.license.bypass=true`
on the game JVM so attach still works without a key.

---

## Layout

```
RoatzBot/
├── src/com/sun/java/fontmgr/       # agent + combat logic
│   └── swap/                       # Advanced-Swapper DSL
├── launcher/src/roatz/launcher/    # branded Swing launcher
├── license-server/                 # Cloudflare Worker license API
├── installer/roatz.iss             # Inno Setup script
├── scripts/                        # issue-key / revoke-key
├── tools/AttachLoader.java         # Dynamic Attach driver (PID -> loadAgent)
├── com/                            # decompiled client classes (gitignored)
├── config/*.gsoft                  # combo config templates
├── build.bat                       # thin wrapper around `gradlew buildAll`
├── attach-agent.{cmd,ps1}          # dynamic attach helper
├── launch.ps1                      # main client launcher
├── launch.sh                       # POSIX launcher (builds + -javaagent)
└── *.bat / *.ps1                   # login/recovery helpers
```

## Prerequisites

- Windows 11 (PowerShell is the primary shell)
- A JDK (the scripts look for
  `C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`, falling back to
  `JAVA_HOME`). The agent itself targets `--release 11`.
- The Roat PKz client installed (the Java runtime is expected under
  `C:\Program Files (x86)\roatpkz_runelite\jre-64\bin\java.exe`).

## Build

```powershell
.\build.bat
# or directly:
.\gradlew.bat buildAll
```

Produces `build\fontmanager-windows.jar` and `build\attach\AttachLoader.class`.

Gradle is the single source of truth for the source list and the manifest
(`build.bat` only forwards to `gradlew buildAll`; it no longer carries its own
`javac` file list). The manifest declares:

- `Premain-Class` / `Agent-Class`: `com.sun.java.fontmgr.FontManager`
- `Can-Redefine-Classes`, `Can-Retransform-Classes`,
  `Can-Set-Native-Method-Prefix`

**ASM is bundled into the agent JAR.** `ClassFilePatcher` uses it to rewrite
client methods; the hand-rolled rewriter it replaced silently dropped exception
tables and `StackMapTable`, so every patched method failed verification with
`VerifyError: Expecting a stackmap frame at branch target N` and the mouse hooks
never applied. The client ships no ASM of its own, so the bundled copy cannot be
shadowed. When changing the patcher, verify a patched class by defining it in a
fresh class loader and linking it (`ClassLoader.resolveClass`) — a `VerifyError`
fails the link, which is exactly the signal the old code never surfaced.

## Launch modes

All modes go through `launch.ps1`.

| Mode | Command | When to use |
|------|---------|-------------|
| Official launcher | `.\launch.ps1 -Official` | Login is broken / client needs an update |
| Vanilla (login-safe) | `.\launch.ps1` | Start vanilla, attach later |
| Attach after login | `.\launch.ps1 -Attach` | Log in first, then attach (recommended) |
| Java agent (premain) | `.\launch.ps1 -Premain` | Inject at JVM startup (overlay in ~10s) |

The recommended flow is **`-Attach`**: launch vanilla, log in, then attach the
agent once in-game so the telemetry patches don't interfere with login.

### Attaching to an already-running client

```powershell
.\attach-agent.ps1 -CommandLineMatch 'roat-rl'
# or, by PID:
.\attach-agent.cmd <pid>
```

## Command socket (optional, off by default)

Enable with `-Dagent.cmd=true`. Listens on `127.0.0.1:9998` (a port banner is a
tell, hence the off-by-default). Protocol is newline-delimited `KEY|value`:

```
PING            -> PONG|ready=1
STATE           -> STATE|hp=..|pray=..|spec=..|tick=..|energy=..|pos=x,y
TICK            -> TICK|<lastTick>
SCRIPT|ENABLE   -> SCRIPT|enabled
SCRIPT|DISABLE  -> SCRIPT|disabled
SCRIPT|SPEC     -> SCRIPT|spec_fired
SCRIPT|STATUS   -> SCRIPT_STATUS|enabled=..|tick=..|autoEat=..
LOG             -> LOG|<last 30 log/warn lines, " ;; " separated>
LOOTER|...      -> LOOTER|...
BYE             -> BYE
```

Optional hardening: set `-Dagent.cmd.token=<secret>` and every connection must
send `AUTH|<secret>` first (the `READY` banner then advertises `auth=required`).
Without that property the socket stays open to any local process, as before.

## Auto-eat toggle

**Num5 (Numpad 5)** or the **Auto Eat** checkbox on the Fight and DH tabs toggles
a master switch over *all* automatic eating:

- DH band / triple eats, the post-greataxe combo (`DH_AXE_EAT`)
- NH spec-survive eats, DH-stack eats, predictive eats
- auto combo-eat

Manual food keys (`1`–`4`, and the NH `A`/`S`/`D` binds) always work.

Turn it **off in Dharok mode**: auto-eat keeps your HP high, and greataxe max hit
scales inversely with HP — that is why the bot felt "safe" and never landed the
big hit. The choice persists in `fontconfig.properties` (`autoeat`).

Other default binds: `1`-`4` eat · `Q` spec combo · `G` gmaul follow · `V` veng
· `Space` ice barrage · `Num9` overheads · `Num0` auto-spec · `Z`/`X`/`C` protect
prayers · `Pause`/`ScrollLock` pause. Z/X/C and the combat keys no longer fire
while you are typing in the swapper editor or an HP field.

## Swapper DSL

Gear swaps are hotkey-bound scripts in Ganom Advanced-Swapper style. Persisted
to `%TEMP%\.cache\fontdata-local.bin`.

Command prefixes: `e:` (equip), `r:` (remove), `drop:`, `p:` (prayer),
`chat:`, `cmd:`, `a:` (attack), `c:`/`select:` (cast/arm spell), `u:`, `o:`,
`spec`, `walkunder`. `|` separates OR alternatives; `//` starts a comment.

See `config/gsoft-ags-gmaul-combo.gsoft` for an example block.

## Login / client recovery

- `.\fix-login.bat` — kill clients, clear `~\.roatpkz\cache` and
  `~\.jagex_cache_32`, then reopen the official launcher after a 90s cooldown.
- `.\fresh-install.bat` — back up `accounts.dat`, wipe `%USERPROFILE%\rpkzclient`,
  and force a fresh client download.

## Notes

- Diagnostics: `FontManager` keeps an always-on 200-line in-memory log tail.
  Read it over the socket (`LOG`) — file logging is still opt-in with
  `-Dagent.filelog=true`. Unresolved client reflection handles and listener
  errors are reported as `WARN` entries so a client update cannot make the agent
  silently inert again.
- The prayer diagnostics file (`fontconfig-pray.dat`) is now written only when
  `-Dfontmgr.praylog=true` (or with file logging on), not on every session.
- The game JAR is located under `%USERPROFILE%\rpkzclient\` and is copied to
  `roat-rl-saved.jar` so the agent has a stable reference between updates.
- Modern gamepacks are ~724 obfuscated classes; class names carry no meaning.
  Always prefer reflection over direct imports against the client.
- Inter-process snapshots (`SharedMemory`) use a seqlock: the sequence word at
  offset `GameState.WIRE_SIZE` is odd while a write is in flight. Readers should
  re-read the payload when the sequence changes or is odd.
