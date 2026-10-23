# Marble Madness (Swing)

A physics-based marble puzzle game for Android, inspired by Marble Madness. Drop marbles onto a board of balance scales, tip a pan to toss its marbles into the air, and clear groups of matching colors for chain-combo points.

The app ships under the name **Swing** (`com.edens.marblemaster`).

## Gameplay

The board is a 9×12 grid carried by four independent balance scales either side of a fixed central pillar:

- **Left deck:** scale A on columns 0–1, scale B on columns 2–3
- **Center:** column 4 is a solid pillar that never tilts
- **Right deck:** scale C on columns 5–6, scale D on columns 7–8

Pivots sit between each scale's two pans, so every marble adds weight. Each turn, tap a column to drop the next marble from a preview queue of three. A five-second clock — the bar above the board — limits that choice: if it empties, the marble drops itself into a random column. Marbles have both a color and a weight (1–9), and each scale tips in real time based on the weight difference between its two pans.

**Mechanics:**

- **Scale physics** — imbalance tips the beam; spring physics return it to level when unloaded. Steep tilts make marbles slide toward the low pan, cascading the stack.
- **Scale tosses** — if one pan becomes much heavier, the light pan flips and *every* marble resting on it is thrown into the air for bonus points (35% chance each to spawn a bonus star). Those marbles arc back down onto the board as special marbles, so a toss always pays off in powerups.
- **Special marbles** — roughly one marble in seven arrives as a powerup:
  - 🧨 **Bomb** — detonates where it lands, clearing a 3×3 square for weight-scaled points.
  - 🌈 **Wild** — colorless; stands in for any color in horizontal runs and in the flood-fill.
  - ⚙️ **Heavy** — iron, always weight 9, and always fires the far pan of its scale for a double launch bonus when dropped.
- **Blob matching** — a horizontal run of 3+ same-color marbles seeds a match that flood-fills in all 8 directions (including diagonals), clearing organic-shaped groups.
- **Chain reactions** — clears compact the columns and can trigger follow-up matches with increasing multipliers.
- **Progression** — every 12 cleared marbles raise the level, adding more colors (up to 6) and heavier marbles (up to 9).
- **Placement timer** — you have five seconds to choose a column; hesitate and the marble drops itself in a random one.
- **Sound & music** — a procedural chiptune loop plus effect sounds for drops, matches, blasts, tosses, landings, level-ups, and game over. Everything is synthesized at runtime, so the APK ships no audio files.
- **Local high scores** — the in-game **HIGH SCORES** button opens a top-ten list of your best runs, kept on the device. Nothing is uploaded and the game needs no network or account.

Game over when any column fills to the top. Your ten best runs are persisted across sessions.

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
    ├── GameEngine.kt    # Pure game logic: physics, matching, chains, levels
    ├── AudioSynth.kt    # Procedural PCM synthesis for effects and the music loop
    ├── GameAudio.kt     # AudioTrack playback, looping music, effect throttling
    ├── HighScores.kt    # Local top-ten high score table
    └── GameView.kt      # Rendering, input, animations, particles, HUD
```

Game logic (`GameEngine`) is fully decoupled from rendering (`GameView`) and communicates through a sealed `GameEvent` hierarchy (Match, Blast, Launched, Landed, Slid, LevelUp, GameOver).

## High scores

A finished run is ranked into a local top-ten list, the **HIGH SCORES** button at the
bottom of the screen shows it, and the game-over panel reports the rank of the run you
just finished. Ties keep the older run ahead. The table stores the score and the date of
each run; it lives in the app's private `SharedPreferences` (`swing` → `high_scores`) and
is never uploaded. The app declares no permissions and makes no network calls.

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

`dist/Swing-1.1-v3.aab` is the signed bundle for `com.edens.marblemaster` (versionCode 3,
versionName 1.1, targetSdk 36), built with `./gradlew bundleRelease`; `dist/SHA256SUMS` holds its
checksum. The previous releases, `dist/Swing-1.1-v2.aab` and `dist/Swing-1.0-v1.aab`, are kept
alongside it. The upload key's SHA-1 is printed by:

```bash
keytool -printcert -jarfile dist/Swing-1.1-v3.aab | grep SHA1
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
