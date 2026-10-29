# Marble Madness (Swing)

A physics-based marble puzzle game for Android, inspired by Marble Madness. Drop marbles onto a board of balance scales, tip a pan to toss its marbles into the air, and clear groups of matching colors for chain-combo points.

The app ships under the name **Swing** (`com.edens.marblemaster`).

## Gameplay

The board is a 9×12 grid carried by four independent balance scales either side of a fixed central pillar:

- **Left deck:** scale A on columns 0–1, scale B on columns 2–3
- **Center:** column 4 is a solid pillar that never tilts
- **Right deck:** scale C on columns 5–6, scale D on columns 7–8

Pivots sit between each scale's two pans, so every marble adds weight. Each turn, tap a column to drop the next marble from a preview queue of three. A shrinking clock — the bar above the board, five seconds at level 1 — limits that choice: if it empties, the marble drops itself into a random column. Marbles have both a color and a weight (1–9), and each scale tips in real time based on the weight difference between its two pans.

**Mechanics:**

- **Scale physics** — imbalance tips the beam; spring physics return it to level when unloaded. Steep tilts make marbles slide toward the low pan, cascading the stack.
- **Overflow spills** — a column that reaches the top does not end the run by itself: the marble that would rest in the top row rolls into the other pan column while that column has room below the top. But a spill is a rescue, not a free move: it costs 20 points (plus 10 per level, never dropping the score below zero) and uses up that column's single spill reserve. The reserve only comes back once you clear the stack down out of the danger zone, so overstacking the same column twice without clearing it ends the run. Columns within two rows of the top pulse amber while their reserve is still there and red once it is spent.
- **Scale tosses** — if one pan becomes much heavier, the light pan flips and *every* marble resting on it is thrown into the air for bonus points (35% chance each to spawn a bonus star). Those marbles arc back down onto the board as special marbles, so a toss always pays off in powerups.
- **Special marbles** — roughly one marble in seven arrives as a powerup:
  - 🧨 **Bomb** — detonates where it lands, clearing a 3×3 square for weight-scaled points.
  - 🌈 **Wild** — colorless; stands in for any color in horizontal runs and in the flood-fill.
  - ⚙️ **Heavy** — iron, always weight 9, and always fires the far pan of its scale for a double launch bonus when dropped.
- **Blob matching** — a horizontal run of 3+ same-color marbles seeds a match that flood-fills in all 8 directions (including diagonals), clearing organic-shaped groups.
- **Streaks** — clears that follow each other within six seconds build a streak: every consecutive clear adds 25% to the points, up to +100%, and a drop that clears nothing ends it. The running streak and its remaining window show in the HUD.
- **Run objectives** — every run rolls three goals from a pool (clear 30 marbles, clear 7 in one blob, toss 6 marbles, reach level 6, build a x3 streak, land 2 cascades, fire 3 powerups). They sit in a strip above the board and fill as you play; each one pays 150 points the moment it is met, so two runs rarely ask the same thing of you. Tap a goal in the strip to read it in full.
- **Progression** — every 12 cleared marbles raise the level, adding more colors (up to 6) and heavier marbles (up to 9).
- **Locked columns** — from level 10 the board starts taking columns away: one closes at level 10, a second at 15 and a third at 20 (a lock is never placed if it would leave fewer than three columns in a row open). A locked column is struck out with a hatch and takes no drops, no spills and no returning tossed marbles, so late runs keep getting harder after the colours, weights and placement clock have all maxed out.
- **Daily challenge** — the **DAILY** button opens the day's seeded board: a fixed seed derived from the date deals the same queue, objectives and launch arcs, so the board is identical all day (and replayable). The panel shows a countdown to the next board, today's best next to yesterday's score to beat, and a consecutive-day streak; daily runs stay out of the endless high-score table.
- **Placement timer** — you start with five seconds to choose a column, and each level shortens the clock by 0.35s down to a 1.8s floor; hesitate and the marble drops itself in a random one.
- **Sound & music** — a procedural chiptune loop plus effect sounds for drops, matches, blasts, tosses, landings, level-ups, and game over. Everything is synthesized at runtime, so the APK ships no audio files. Music, effects and vibration can each be switched off in **SETTINGS**.
- **Local high scores** — the in-game **SCORES** button opens a top-ten list of your best runs, kept on the device. Nothing is uploaded and the game needs no network or account.
- **Stats & achievements** — the **STATS** panel shows lifetime totals (best level, best score, biggest clear, marbles cleared and tossed, spills survived, runs played) and six achievements unlocked by play: First Swing, Blast Off, Blob Buster, High Climber, Century and Point Hoarder. Its **HOW TO PLAY** link reopens the walkthrough. All of it stays on the device.

Game over when a marble has to rest in the top row and its column has no spill left to give — the pan's other column is full, or the column already spent its reserve without clearing. Your ten best runs are persisted across sessions.

## Tech Stack

- **Language:** Kotlin 2.1
- **Platform:** Android (minSdk 24 / Android 7.0+, targetSdk 36 / Android 16)
- **Rendering:** Custom 2D `Canvas` renderer — no game engine
- **Physics:** Custom seesaw/projectile simulation in pure Kotlin
- **Audio:** Procedural PCM synthesis (chiptune music loop and one-shot effects) played through `AudioTrack`
- **High scores:** on-device top-ten table in `SharedPreferences` (no network)
- **Dependencies:** Kotlin stdlib only
- **Build:** Gradle 8.13 + Android Gradle Plugin 8.13

## Project Structure

```
app/src/main/java/com/edens/marblemaster/
├── MainActivity.kt      # Immersive fullscreen activity hosting the game
└── game/
    ├── Config.kt        # All tuning constants (physics, scoring, board layout)
    ├── Models.kt        # Data models: Marble, PowerKind, Seesaw, GameEvent, etc.
    ├── Objectives.kt    # Per-run goal kinds and the progress of a rolled goal
    ├── GameEngine.kt    # Pure game logic: physics, matching, chains, levels
    ├── AudioSynth.kt    # Procedural PCM synthesis for effects and the music loop
    ├── GameAudio.kt     # AudioTrack playback, looping music, effect throttling
    ├── HighScores.kt    # Local top-ten high score table
    ├── PlayerProgress.kt # Persisted lifetime stats and achievements
    ├── DailyProgress.kt # Daily-challenge seed, day streak and daily bests
    └── GameView.kt      # Rendering, input, animations, particles, HUD
```

Game logic (`GameEngine`) is fully decoupled from rendering (`GameView`) and communicates through a sealed `GameEvent` hierarchy (Match, Blast, Launched, Landed, Slid, LevelUp, ObjectiveDone, GameOver).

## High scores

A finished run is ranked into a local top-ten list, the **SCORES** button at the
bottom of the screen shows it, and the game-over panel reports the rank of the run you
just finished. Ties keep the older run ahead. The table stores the score and the date of
each run. A **HISTORY** tab beside it lists the last twenty runs — score, level reached and
date, newest first — so progress over time is visible rather than only the best ten.
Both live in the app's private `SharedPreferences` (`swing` → `high_scores` and `history`)
and are never uploaded. Lifetime stats and unlocked achievements sit beside them under
`progress`, the daily challenge's date, streak and bests under `daily`, and the three
settings toggles as `music`, `effects` and `haptics`. Daily runs are deliberately kept out
of the top-ten table, because everyone plays the same seeded board. The app declares no
permissions and makes no network calls.

A single best score saved by an earlier version is migrated into the list on first launch.
Clearing the app's storage resets the table.

## Building

Requires the Android SDK (API 36) and JDK 17+.

```bash
./gradlew assembleDebug    # Build debug APK
./gradlew installDebug     # Install to a connected device
./gradlew assembleRelease  # Build signed release APK (for sideloading)
./gradlew bundleRelease    # Build signed release AAB (the Play upload artifact)
```

Release builds are signed with the upload key configured in `keystore.properties` (see
`keystore.properties.example`). That file and `keystore/` are gitignored; back both up, because
losing the upload key means resetting it in Play Console before the next release. Without
`keystore.properties` the release build still runs but produces an unsigned archive.

Before each upload bump `versionCode` (must increase) and `versionName` in `app/build.gradle`.

## Release artifact

`dist/Swing-1.2-v4.aab` is the signed bundle for `com.edens.marblemaster` (versionCode 4,
versionName 1.2, targetSdk 36), built with `./gradlew bundleRelease`; `dist/SHA256SUMS` holds its
checksum. The previous releases, `dist/Swing-1.1-v3.aab`, `dist/Swing-1.1-v2.aab` and
`dist/Swing-1.0-v1.aab`, are kept alongside it. The upload key's SHA-1 is printed by:

```bash
keytool -printcert -jarfile dist/Swing-1.2-v4.aab | grep SHA1
```

Upload key: `CN=Swing Upload Key, O=Marble Madness`, SHA-1
`87:78:33:EA:A5:6F:C7:21:F0:39:44:0E:EB:CA:8E:03:1B:AB:BB:61`. Play Console will ask to enrol
the app in Play App Signing on the first upload; keep this keystore as the upload key.

The game runs in portrait-locked immersive fullscreen with display-cutout support and haptic feedback on matches and launches.

## Play Store assets

Store graphics, listing copy and the screenshot set live in [`store/`](store/README.md).

```bash
python3 store/tools/generate_graphics.py   # icon, feature graphic, legacy launcher PNGs
python3 store/tools/verify_assets.py       # validates sizes, formats and text limits
```

`app/src/main/res/mipmap-*/ic_launcher*.png` are generated fallbacks for API 24–25, where the
adaptive icon in `mipmap-anydpi-v26` is not used. See [store/README.md](store/README.md) for the
publishing checklist (signed AAB, privacy policy, data safety).

## Privacy policy

[`docs/privacy-policy.html`](docs/privacy-policy.html) is the public policy for the Play listing: a
single static page stating that high scores stay on the device and that the game has no ads, analytics,
trackers, or network access. It loads no scripts and sets no cookies.

Publish it with GitHub Pages (Settings → Pages → Deploy from a branch: `main`, `/docs`), which serves
`https://peterede.github.io/MarbleMaster/privacy-policy.html`. Edit the contact email and the
“Last updated” date there if the game's data handling changes.
