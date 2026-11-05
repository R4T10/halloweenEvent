# Halloween Event (Forge 1.20.1 / 47.x)

Mod id `halloweenevent`. Required on **both** server and clients (custom network channel and client rendering).
Hold Hands, Carry On and Freecam are optional and detected at runtime.

## Build

Requires JDK 17. ForgeGradle 6, Forge `1.20.1-47.4.20`, official mappings.

```bash
./gradlew build
```

Output: `build/libs/halloweenevent-1.0.0.jar`. Put it in the `mods` folder of the server and every client.

> Windows: build from a short path (for example `C:\hw\`). Very long project paths break ForgeGradle's tooling.

The three integration JARs live in `libs/` and are compiled with `compileOnly fg.deobf("blank:<name>:<version>")`
via a `flatDir` repository. They are not bundled.

| libs file | Source JAR | Mod id |
|---|---|---|
| `holdhands-1.1.4.jar` | `holdhands-1.1.4.jar` | `holdhands` |
| `carryon-2.1.2.7.jar` | `carryon-forge-1.20.1-2.1.2.7-netfix.jar` | `carryon` |
| `freecam-1.1.1.jar` | `freecam-1.1.1.1.1.1.1.1.jar` | `freecam` |

`./gradlew runClient` also loads Embeddium 0.3.31 and Oculus 1.7.0 (`runtimeOnly fg.deobf(...)` from the
Modrinth maven, dev run only, never bundled) so shader packs can be tested: put packs in `run/shaderpacks` and
pick one under Options > Video Settings > Shader Packs. The first run needs internet to download them.

Textures are generated (already run; PNGs are committed under `src/main/resources`):

| Command | Writes |
|---|---|
| `node tools/gen_textures.mjs` | pixel-art GUI and candy items |
| `node tools/gen_realistic.mjs [face\|figure\|effects]` | the shaded, realistic textures: jumpscare face, figure skin atlas (1024 px, 4 texels per UV unit), mist, vignette, static, clawed hand, eyes. Smooth filtering is enabled with a `.png.mcmeta` (`blur: true`) next to each. |
| `node tools/gen_arena.mjs` | the empty test arena used by the game tests |

(`gen_textures.mjs` also draws the flashlight item, off and lit.)

## Using it

1. `/halloween admin` (OP level 2) opens the panel.
2. **Event tab**: stand in one corner, *Set corner 1 here*, walk to the opposite corner, *Set corner 2 here*.
3. **Candy tab**: walk inside the zone and press *Add candy here* at each spot. Set *Candies to win* (default 15)
   and the mode:
   - *Per player* (default): every player can take each candy once and only stops seeing the ones they took.
     You need at least as many spawns as the target.
   - *Shared*: first player to touch a candy takes it, it reappears after the respawn time.
4. **Settings tab**: hold the reward item and press *Use held item*. Optionally add a reward command
   (`%player%` = winner name, runs once as the server).
5. **Players tab**: per-player scare level (1-3) and the default level.
6. *Start event*. Also: `/halloween start|stop|status`.

Players see a candy counter top-left. With the target reached the HUD says *Escape the zone*; walking out
completes the event and pays the reward exactly once. Leaving early keeps progress. Dying never counts as
escaping (they must re-enter and walk out).

All settings and progress persist in `<world>/data/halloweenevent.dat`.

## Integrations (verified against the supplied JARs)

### Hold Hands 1.1.4
`com.seaz.holdhands.HoldManager` is public and server-side:
`isHolding(UUID)` (entry in its `HELD` map) and `partnerOf(ServerPlayer)` (online partner).
A player counts as holding hands only when both are true. Read on the server thread only, every 5 ticks.

Scare ladder per player inside the zone, timed from the moment they stopped holding a hand:

| Time alone | Level 1 | Level 2 | Level 3 |
|---|---|---|---|
| 5 s | whisper | whisper | louder whisper |
| 15 s | horror text | horror text | more text + glitches |
| 30 s | whisper + text | shadow figure | figure, lunge, face flash, shake, scream, **1 damage** |

After the 30 s stage the ladder restarts, so damage is one point per full sequence, never per tick.
Grabbing a hand resets the timer and sends one stop packet. If Hold Hands is missing, these scares are disabled.

### Carry On 2.1.2.7
- Carry state: `CarryOnDataManager.getCarryData(player).isCarrying()` (blocks, entities **and players** - this
  build has `CarryType.PLAYER`, so player carrying is real and covered).
- While carrying inside the zone: persistent face overlay + heartbeat until the object is placed.
- *Carry auto-drop* (default 5 s, 0 = off) calls `PlacementHandler.placeCarried(player)`, the same routine
  Carry On uses when a player dies.
- *Block Carry On pickups in zone* (off by default):
  - entities/players: cancels Carry On's `@Cancelable EntityPickupEvent`;
  - blocks: Carry On has no pickup event; its `RightClickBlock` listener is `HIGH` priority and skips cancelled
    events, so we cancel at `HIGHEST` only when `PickupHandler.canCarryGeneral(...)` says Carry On would pick up.

### Freecam (net.xolt.freecam)
Client-side check every tick while the player is inside an active zone:
- Freecam installed: `Freecam.isEnabled()` (free + tripod mode) -> `Freecam.toggle()` turns it off through the
  mod's own code.
- Any other mod that swaps the camera entity: detected by `getCameraEntity() != player`, forced back with
  `setCameraEntity(player)`.
- A 3 s full-screen face + scream plays on every detection. The client reports the flag (rate-limited) to the
  server, which logs it and shows *Freecam* in the admin Players tab.

**Limitations**: the server cannot see a client's camera. A camera mod that moves the view without changing
the camera entity (mixins into `Camera.setup`), or a modified client, can bypass this. The guard also fires
for camera-entity swaps by other mods (for example CMDCam path playback) while inside the zone; disable it
with *Freecam guard* if you run cutscenes there.

## Fog, figures, glitches (shader packs)

- **Fog**: see *Zone fog* below. Level 3 adds a 2.5 s vanilla Blindness pulse at the figure, which shader
  packs honour too.
- **Figures**: four original cuboid models (ModelPart, like vanilla mobs), picked at random, never the same
  twice in a row:
  - *Hatted*: ~3 blocks, floating ragged cloak with swaying tatters, wide-brim hat with a red band, long pale
    cracked mask with red-rimmed eyes and a red-gummed grin, long black hair. Appearing triggers the
    hypnosis eye.
  - *Red Thin*: ~3.4 blocks, red ribbed flesh fading to black limbs, very long neck, tiny head with dot eyes
    that never blink; turns its head too far, too slowly.
  - *Grinner*: hunched black furry beast, floppy ears, big glowing red eyes, wide grin of small teeth,
    dog-like head tilt.
  - *Crawler*: on all fours, spine ridges, crawls towards you.

  All have animated eyes: they blink, glance away and snap back to stare at you, pulse, and go wide with
  pin-prick pupils when lunging. They twitch, flicker in, trail smoke, and at level 3 lunge into your face.
- **Screen eyes** (GUI layer, any shader pack): almond eyes open around the screen edges, blink and stare at
  the centre (15 s scare at level 2+, grab, chase start); at a level 3 lunge or when the chaser catches you the
  whole screen fills with eyes. The *hypnosis eye* is a big red eye with a rotating spiral iris that makes the
  field of view breathe. Test with `/halloween test eyes`, `/halloween test eyes wall`, `/halloween test hypno`.
- **Glitch**: always a GUI overlay (TV static strips, colour bars, corrupted blocks, scanlines, dropped
  frames). Without a shader pack it also loads `halloweenevent:shaders/post/glitch.json`
  (row tearing, RGB split, block corruption, red/invert flashes). With an Oculus shader pack active the post
  shader is skipped, detected via `IrisApi.isShaderPackInUse()` (reflection, no dependency).

## Zone fog

Always on inside an active zone: a **solid wall** of pale grey, drifting wisps over the whole screen, near or far
(the look of `feature/new-dark-figures-eyes`, where a depth bug made the fog opaque; here on purpose so every GPU
shows the same). Core shader `halloweenevent:zone_fog`, run through `FullscreenPass`, tinted by the world's own
fog colour, dimmed at night, red at scare level 3.

Only **flashlight beams cut through it**: where a lit beam (yours, or another player's within 32 blocks, up to 4)
reaches a surface, the wall opens and the lit world shows, closing back towards the end of the beam (13 blocks).
The opening follows the beam's brightness, so flicker, brown-outs and kills close the fog in again. A textured
mist (two drifting layers of `mist.png`) is drawn on the screen on top.

Scares thicken it as the ladder climbs (5 s, 15 s, 30 s). The thickness is **the same at levels 1, 2 and 3**;
only level 3 tints it (and the chase's fog) red. Your own lit flashlight thins the fog inside its cone near you
(`beamClear` in the shader). Tuned in `ZoneFogRenderer.draw()`.

Without a shader pack the main depth texture is sampled directly (a blitted copy read as all sky on some GPUs,
which made flat full-screen fog). With a shader pack the fog is drawn after Oculus has composited the frame,
from a depth copy taken at `AFTER_LEVEL`. A rendering error switches the zone fog off (vanilla fog) and logs
it once instead of crashing every frame.

Depth is captured at `AFTER_LEVEL`; Oculus renders into the same main depth texture, so it also works with
shader packs (drawn after Oculus composites the frame; then the held item is fogged too, and F1 hides the fog).
If the shader fails to load, the game still starts and falls back to vanilla fog + a mist overlay.

## Dark-figure drag and chase (Hold Hands)

Every *grab interval* (Horror tab, default 90 s, 0 = off) each hand-holding pair in the zone has a 50% chance:

1. A shadow figure (Tall, Smiler or Broken) rises next to one of them and reaches with both arms; both partners see it.
2. After 0.6 s Hold Hands' own `release(..., SLIPPED, partner)` splits the pair and the figure drags the victim
   for 1.2 s (per-tick pull) to a random standable spot inside the zone about 10 blocks from the partner. If they
   get stuck on terrain they are snapped to that spot.
3. The victim sees a black clawed hand gripping the screen from the side they are pulled towards (shaking while
   dragged, red vignette), then a shadow figure sprints at them for 10 s. The chaser is a client-side
   hallucination only the victim sees; a catch is a face flash + scream, no damage.

## Flashlight

Event-only, no battery, our own code. Everyone inside the active zone gets exactly one (off hand if free, so the
main hand stays free for holding hands; switched off). Leaving the zone, dying or stopping the event takes it
away. Checked every 5 ticks over the whole inventory (main, armour, off hand, crafting grid, cursor); it can not
be tossed (it jumps back), dropped on death, left in a chest (removed when the chest closes) or exist as an item
in the world. Not in any creative tab. Right-click switches it on and off.

- A weak, dying torch on purpose: bright core, 24° main cone, faint spill to 38°, reaches 13 blocks, dim warm
  light. A full-screen pass over the depth buffer (core shader `halloweenevent:flashlight`, same timing as the
  zone fog, so it works with Oculus too). Lights surfaces before the zone fog; your own beam only thins the fog
  near you (at most 55%), so what is further out stays hidden.
  Up to 4 beams at once (yours first, then the nearest players within 32 blocks), so a 200-player crowd costs the
  same as 4. No extra packets: beams come from the held item's NBT.
- Phenomena scale with the player's scare level (Players tab, 1-3), stored on the item as `Fear`:

  | | Level 1 | Level 2 | Level 3 |
  |---|---|---|---|
  | Flicker bursts | rare | normal | frequent, longer |
  | Brown-outs (light sags for ~4 s) | - | sometimes, to 45% | often, to 20% |
  | Random kill (alone; half as often holding hands) | ~3 min | ~90 s | ~50 s |
  | Kill cooldown | 60 s | 40 s | 25 s |
  | Struggling switch-on gives up | never | 15% | 35% |
  | Beam turns red with the red fog | - | - | yes |

  The scare ladder also kills it (15 s step from level 2, 30 s figure) and so does a dark-figure grab.
- A killed flashlight stays off until switched on again. That switch-on struggles for ~2 s (strain whine, stutter,
  clicks); if it gives up it nearly catches, then dies again and needs another try.
- Test: `/halloween test flashlight [player]` kills the target's lit flashlight.
- Dev only: `F8` cycles debug views of the beam pass (block grid, light amount, distance); `[Flashlight]` and
  `[ZoneFog]` client logs. Automated check: copy a world to `run/saves/flashlight_test`, then
  `./gradlew runClient -PflashlightTest`. It builds a small scene, screenshots fog-only plus every debug view
  from 11 angles into `run/screenshots/fltest_*.png`, checks the inventory rules (PASS/FAIL in the log) and quits.

Notes for the depth passes (flashlight and zone fog):
- The view rotation is rebuilt from the camera angles plus the recorded roll: the PoseStack handed to
  `RenderLevelStageEvent` at `AFTER_LEVEL` is not the camera matrix (positions rebuilt with it all collapse
  onto the camera).
- Both run through `FullscreenPass`: they read the main colour and depth as textures, draw into a colour-only
  scratch target and copy the result back. Sampling the depth of the framebuffer being drawn into is a feedback
  loop; with compressed depth the GPU returned "sky" for whole tiles (hard-edged holes at fixed screen positions).
- Without a shader pack they read the main depth texture directly; with one, a copy taken at `AFTER_LEVEL`
  (untested with Oculus).
- The fog mixes itself into the scene colour (no GL blending). `ShaderInstance.apply()` skips a blend mode that
  was the last one applied, which used to draw the fog opaque (a grey screen).
- No light scattered in the air: the edge of the beam's volume showed as hard lines fanning out from the hand.

State lives in the item NBT (`On`, `Dead`, `StartAt`, `Fail`, `Fear`). Other players see your beam and the
struggle through the vanilla equipment sync; the stutter and its clicks are worked out on each client.

## Language

All text is Thai. `en_us.json` contains the same Thai strings so English clients also see Thai.

## Sounds

No OGG encoder was available, so custom sound events (`halloweenevent:whisper`, `scream`, ...) are defined in
`sounds.json` from pitch-shifted vanilla samples (soul sand valley whispers, enderman stare/screams, warden
heartbeat). To use your own recordings, ship a resource pack with
`assets/halloweenevent/sounds/*.ogg` and override `sounds.json`; no code change is needed.

## Performance and network

- Server work runs every 5 ticks: per online player an AABB test, a scan of the candy list (<= 256) only while
  inside the zone, two map lookups. No entity or block scanning, no per-tick packets.
- Candies are not entities or blocks: positions are synced once (on login, start/stop, edits) and the client
  renders floating items. Per-player hiding is free.
- Packets: status (on change), candy list (rare), candy visibility (on pickup), 2-byte scare triggers (once per
  stage), admin snapshot (admin only). All animation, randomness and sound timing is client-side.
- No mixins.

## Automated tests

```bash
./gradlew runGameTestServer      # 28 game tests on a headless server, about 35 s
node tools/validate_mod.mjs      # static checks for client assets, shaders, language keys and packets
```

In-game tests (they open a copy of a world saved as `run/saves/flashlight_test`, build a flat night arena inside a
zone of their own, save screenshots to `run/screenshots` and quit; run from a short path, see above):

```bash
./gradlew runClient -PscareTest   # every jumpscare the client can show: scare_*.png
./gradlew runClient -PgrabTest    # a bot partner holds your hand (third person), then you / the bot are dragged away: grab_*.png
./gradlew runClient -PflashlightTest   # the flashlight, fog debug views and inventory checks: fltest_*.png
```

Multiplayer run (a real dedicated server and two real clients, each from its own folder under `run_mp/`; start
each in its own terminal, the clients connect by themselves from the title screen and close when the server stops):

```bash
./gradlew runServer -PmpServer         # run_mp/server: eula, offline mode, RCON on 127.0.0.1 only (see server.properties)
./gradlew runClient -PmpUser=PlayerA
./gradlew runClient -PmpUser=PlayerB
node tools/mp_test.mjs                 # drives the server over RCON; PASS/FAIL per check, then stops the server
```

`run_mp/server/mp_test` is a copy of an event world (zone around -27 -60 -9; other zones: pass `x y z` to
`mp_test.mjs`). It checks: both join; no flashlight outside the zone, exactly one inside; each player switches it
on with a real right-click; the test kill switches it off and the player relights it through the struggle; the
figure, eyes, eye wall, hypno and chase reach their player; leaving takes the flashlight, coming back returns it;
stopping the event takes every flashlight. Screenshots every 3 s in `run_mp/<player>/screenshots`, with a state
line per shot in that client's log (`[MP PlayerA] ...`). The dark-figure grab needs two players holding hands
(a key press), so it is covered by the game tests and `-PgrabTest` instead. Connect by hand with `127.0.0.1`,
not `localhost` (the server listens on IPv4 loopback only).

The game tests use **bot players**: real `ServerPlayer`s placed in the player list (so the mod's code sees them
like connected players), standing in an empty stone arena (`tools/gen_arena.mjs`). Two bots hold hands through
Hold Hands' own calls (`HoldManager.onKey(a, b)` then `onKey(b, a)`, the same as the H key). Each bot's
connection records every packet the server sends it, so the tests also check what real clients would receive.
The test code lives in `com.halloweenevent.gametest` and is **not packaged** into the release JAR.

| Area | What is checked |
|---|---|
| Event | candy counted once per player, shared mode and respawn, reward paid exactly once (item and command), leaving early keeps progress, death never wins and re-entering works, nothing happens while stopped, the zone is exactly its box |
| Hold Hands | bots can hold and let go; alone at level 3 costs exactly 1 health per 30 s sequence and never repeats; levels 1-2 never damage; holding hands (from the start or taken late) resets the timer |
| Jumpscares | four pairs take hands before the whisper, after it, after the text and after the figure: each gets exactly the scares so far, then STOP; letting go restarts from the whisper only; leaving the zone ends the scares and re-entering starts fresh; every `/halloween test` scare reaches only its target with the right type and level; the chase carries the victim's level 1, 2 and 3 |
| Dark figure | the test grab drags a held player about 10 blocks from the partner every time, anywhere; the timed grab splits a couple inside the zone; no grab when the interval is off; a partner or victim disconnecting is safe |
| Packets | every packet round-trips; the scare ladder sends whisper, text, figure in order at the player's level and a stop when hands are taken; the grab goes to both players and the chase only to the victim, with the victim's level; candy and status go only to the right players; admin data only to operators |
| Admin and commands | operator-only, every value clamped, malformed input ignored; commands hidden from non-operators, test commands work |
| Saving | every setting and player progress survives save and load; bad values are clamped |

I broke the mod's code on purpose (damage 2, no timer reset, partner not told about a grab) to confirm each
check fails with a clear message.

**Not covered** (cannot run on a headless server): rendering, sounds and screens (checked by
`validate_mod.mjs` and by playing); **Carry On** (its mixins do not load in a dev server) and **Freecam**
(client-only), so those two integrations are only compile-checked against the supplied JARs.

## Multiplayer test checklist

1. Dedicated server + 2 clients with Hold Hands, Carry On, Freecam. Non-OP runs `/halloween admin` -> unknown command.
2. OP sets corners, adds 15+ candies, starts. Both clients see candies; HUD appears on entering the zone.
3. Player A takes a candy: it disappears for A only (per-player mode), counter goes up, B still sees it.
   Walk over the same spot again -> no double count.
4. Shared mode: A takes a candy -> gone for both; returns after the respawn time.
5. A leaves with 7 candies -> no reward; returns -> counter still 7.
6. A reaches 15 -> *Escape the zone* -> walks out -> reward once, broadcast. Re-enter/leave -> no second reward.
   Restart the server -> still completed.
7. Die inside the zone with 15 candies -> respawn outside -> not completed until re-entering and walking out.
8. A and B hold hands (H key) -> no scares. Release -> whisper at 5 s, text at 15 s, figure at 30 s (level 2).
   Grab hands mid-sequence -> effects stop immediately and the timer restarts.
9. Level 3 on A: glitches, lunge, flash; health drops by exactly 1 heart-half per 30 s cycle.
10. Carry a chest in the zone -> face + heartbeat; place it -> face ends. With auto-drop 5 s it is placed for
    you. Enable *Block Carry On pickups* -> shift-right-click does nothing, message shown; doors still work
    with an item in hand.
11. Toggle freecam inside the zone -> camera snaps back, face flash; admin Players tab shows *Freecam*.
    Outside the zone -> freecam works normally.
12. Stop event / disconnect / change dimension while scared or carrying -> all overlays clear.
