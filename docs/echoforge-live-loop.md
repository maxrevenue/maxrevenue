# EchoForge live loop: record → fixture → replay

EchoForge is the headless `com.automation.core` Sense-Think-Act engine plus a
golden-master replay harness. It is **not** bundled into
`fontmanager-windows.jar` — the agent records ticks, the engine replays them
offline under `./gradlew coreTest`.

```
agent onTick ──► TickRecorder (NDJSON) ──► core/src/test/resources/fixtures/
                                                    │
                                        ./gradlew coreTest
                                                    │
                                    BehaviorTreeDecisionEngine vs recorded action
```

## 1. Headless run (no client)

```powershell
git switch cursor/echoforge-live-loop-3f6a
.\gradlew.bat coreTest
# report: build\reports\tests\coreTest\index.html
```

`coreTest` is now part of `buildAll` and `check`, so it also runs on
`.\gradlew.bat buildAll`.

## 2. Record a live session

`launch.ps1` has a `-Rec` switch that enables the recorder in either mode:

```powershell
# attach-after-login (recommended) + record to an absolute path
.\launch.ps1 -Attach -Rec C:\Users\Alec\Desktop\RoatzBot\logs\replays\session.ndjson

# or premain
.\launch.ps1 -Premain -Rec C:\Users\Alec\Desktop\RoatzBot\logs\replays\session.ndjson
```

- `-Rec true` uses the default `logs\replays\tick_session_<stamp>.ndjson`, which is
  relative to the **game client's** working directory, so prefer an absolute path.
- `-Premain` adds `-Droatz.rec=<path>` to the game JVM. `-Attach` cannot set a
  JVM property on an already-running client, so it passes `rec=<path>` as the
  agent arg instead; `FontManager.AgentOptions` turns that into the same property.
- The resolved path is logged at startup (`[rec] EchoForge recording ticks to …`),
  readable over the command socket with `LOG`.
- The writer flushes every second and on JVM exit. **Quit the client** to be
  certain it is flushed; detaching alone does not run the shutdown hook.

Each line is one tick:

```json
{"tickCount":13,"player":{"hp":55,"maxHp":99,"prayer":33,"specEnergy":50,
 "equipment":{"3":11802},"inventory":{"3":3144}},
 "target":{"hp":20,"maxHp":99,"weaponId":12006,"animationId":-1,"distance":-1},
 "executedAction":"SPEC:","actionLabel":"BIGHIT_SPEC@13"}
```

`executedAction` is the compact vocabulary the loader asserts against
(`EAT:` / `SPEC:` / `ATTACK`); `actionLabel` keeps the raw agent label for
debugging. The mapping lives in `TickRecorder.compactExecutedAction` and is
pinned by `TickRecorderActionTest`. Ticks that are observational, failed
attempts (`..._MISS`, `NOENERGY`, `NO_WIELD`, `HOLD`, `SKIP`, `FAIL`, `ERR`), or
unrecognized map to `""` and are skipped rather than mis-asserted.

## 3. Turn a recording into a fixture

1. Copy the `.ndjson` into `core/src/test/resources/fixtures/`.
2. That is all — `NDJSONFixtureLoader.fixtureResourceNames()` scans the
   directory, so new files are picked up automatically (no list to edit).
3. Re-run `./gradlew coreTest`.

If a file parses to nothing but `OTHER` ticks, `GoldenReplayTest
.fixturesCarryAssertableActions` fails with the breakdown instead of passing
silently.

## Notes / limits

- `distance` is recorded as `-1`; the engine does not read it today.
- The engine models eat / spec / attack. Prayer and gear ticks are
  observational and are skipped by the golden replay.
- The engine is a parallel implementation; recordings are the oracle it is
  compared against, not something it drives live (yet).
