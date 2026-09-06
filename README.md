# Roat PKz Agent (FontManager)

A Java Instrumentation agent that attaches to the Roat PKz client and drives
PK combat automation. The agent is packaged under an innocuous
`com.sun.java.fontmgr` namespace and supports both load-time (`-javaagent`)
and runtime (Dynamic Attach) injection.

> **Disclaimer:** This tool automates game play in violation of Roat PKz's
> terms of service. It is published as a reverse-engineering and JVM
> instrumentation exercise only. Use of the "stealth" layer (agent token
> scrubbing, `AhkDetection` neutralization, imitation cache paths) is
> detection-avoidance and carries account-ban risk.

---

## Layout

```
RoatzBot/
├── src/com/sun/java/fontmgr/       # agent + combat logic
│   └── swap/                       # Advanced-Swapper DSL
├── tools/AttachLoader.java         # Dynamic Attach driver (PID -> loadAgent)
├── com/                            # decompiled client classes (gitignored)
├── config/*.gsoft                  # combo config templates
├── build.bat                       # compile + package the agent
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
```

Produces `build\fontmanager-windows.jar` and `build\attach\AttachLoader.class`.

The agent manifest (`manifest.mf`) declares:

- `Premain-Class` / `Agent-Class`: `com.sun.java.fontmgr.FontManager`
- `Can-Redefine-Classes`, `Can-Retransform-Classes`,
  `Can-Set-Native-Method-Prefix`

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
SCRIPT|STATUS   -> SCRIPT_STATUS|enabled=..|tick=..
BYE             -> BYE
```

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

- The game JAR is located under `%USERPROFILE%\rpkzclient\` and is copied to
  `roat-rl-saved.jar` so the agent has a stable reference between updates.
- Modern gamepacks are ~724 obfuscated classes; class names carry no meaning.
  Always prefer reflection over direct imports against the client.
