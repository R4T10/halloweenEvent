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

Textures are generated (already run; PNGs are committed under `src/main/resources`):

| Command | Writes |
|---|---|
| `node tools/gen_textures.mjs` | pixel-art GUI and candy items |
| `node tools/gen_realistic.mjs [face\|figure\|effects]` | the shaded, realistic textures: jumpscare face, figure skin atlas (1024 px, 4 texels per UV unit), mist, vignette, static, clawed hand, eyes. Smooth filtering is enabled with a `.png.mcmeta` (`blur: true`) next to each. |
| `node tools/gen_arena.mjs` | the empty test arena used by the game tests |

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

Always on inside an active zone: pale grey, drifting, world-anchored wisps drawn in a full-screen pass over a
copy of the depth buffer (core shader `halloweenevent:zone_fog`), tinted by the world's own fog colour and dimmed
at night. Alone: starts at 4 blocks, thickest by 30 (max 55%). Holding a hand: starts at 8, by 40, max 35%.
A textured mist (two drifting layers of `mist.png`) is drawn on the screen on top.

Scares thicken it as the ladder climbs (5 s, 15 s, 30 s). The 30 s stage is **equally thick at levels 1, 2
and 3**; only level 3 tints it (and the chase's fog) red.

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
./gradlew runGameTestServer      # 24 game tests on a headless server, about 35 s
node tools/validate_mod.mjs      # static checks for client assets, shaders, language keys and packets
```

The game tests use **bot players**: real `ServerPlayer`s placed in the player list (so the mod's code sees them
like connected players), standing in an empty stone arena (`tools/gen_arena.mjs`). Two bots hold hands through
Hold Hands' own calls (`HoldManager.onKey(a, b)` then `onKey(b, a)`, the same as the H key). Each bot's
connection records every packet the server sends it, so the tests also check what real clients would receive.
The test code lives in `com.halloweenevent.gametest` and is **not packaged** into the release JAR.

| Area | What is checked |
|---|---|
| Event | candy counted once per player, shared mode and respawn, reward paid exactly once (item and command), leaving early keeps progress, death never wins and re-entering works, nothing happens while stopped, the zone is exactly its box |
| Hold Hands | bots can hold and let go; alone at level 3 costs exactly 1 health per 30 s sequence and never repeats; levels 1-2 never damage; holding hands (from the start or taken late) resets the timer |
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
