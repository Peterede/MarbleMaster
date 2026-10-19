# Marble Madness (Swing)

A physics-based marble puzzle game for Android, inspired by Marble Madness. Drop marbles onto a tilting seesaw board, balance weights to catapult rivals into the air, and clear groups of matching colors for chain-combo points.

The app ships under the name **Swing** (`com.marblemadness.swing`).

## Gameplay

The board is a 7×12 grid split into two independent seesaws with a fixed central pillar:

- **Left seesaw:** columns 0–2 (pivot at column 1)
- **Right seesaw:** columns 4–6 (pivot at column 5)
- **Center:** column 3 is a solid pillar that never tilts

Each turn, tap a column to drop the next marble from a preview queue of three. Marbles have both a color and a weight (1–9), and each seesaw tilts in real time based on the weight imbalance between its two ends.

**Mechanics:**

- **Seesaw physics** — imbalance tips the board; spring physics return it to level when unloaded. Steep tilts make marbles slide toward the low end, cascading the stack.
- **Catapult launches** — if the imbalance is extreme, the lightest marble on the heavy side's opponent end is launched as a projectile, scoring bonus points (35% chance to spawn a bonus star).
- **Blob matching** — a horizontal run of 3+ same-color marbles seeds a match that flood-fills in all 8 directions (including diagonals), clearing organic-shaped groups.
- **Chain reactions** — clears compact the columns and can trigger follow-up matches with increasing multipliers.
- **Progression** — every 12 cleared marbles raise the level, adding more colors (up to 6) and heavier marbles (up to 9).

Game over when any column fills to the top. Best score is persisted across sessions.

## Tech Stack

- **Language:** Kotlin 1.9
- **Platform:** Android (minSdk 24 / Android 7.0+, targetSdk 34)
- **Rendering:** Custom 2D `Canvas` renderer — no game engine
- **Physics:** Custom seesaw/projectile simulation in pure Kotlin
- **Dependencies:** None beyond the Kotlin stdlib
- **Build:** Gradle + Android Gradle Plugin 8.2

## Project Structure

```
app/src/main/java/com/marblemadness/swing/
├── MainActivity.kt      # Immersive fullscreen activity hosting the game
└── game/
    ├── Config.kt        # All tuning constants (physics, scoring, board layout)
    ├── Models.kt        # Data models: Marble, Seesaw, GameEvent, etc.
    ├── GameEngine.kt    # Pure game logic: physics, matching, chains, levels
    └── GameView.kt      # Rendering, input, animations, particles, HUD
```

Game logic (`GameEngine`) is fully decoupled from rendering (`GameView`) and communicates through a sealed `GameEvent` hierarchy (Match, Launched, Slid, LevelUp, GameOver).

## Building

Requires the Android SDK (API 34) and JDK 8+.

```bash
./gradlew assembleDebug    # Build debug APK
./gradlew installDebug     # Install to a connected device
./gradlew assembleRelease  # Build release APK
```

The game runs in portrait-locked immersive fullscreen with display-cutout support and haptic feedback on matches and launches.
