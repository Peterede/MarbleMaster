# Play Store assets

Everything Play Console asks for (graphics + listing copy), generated from the game's own palette so the
store art and the game look like the same product.

```
store/
├── graphics/
│   ├── app-icon-512.png            # 512x512, 32-bit PNG, fully opaque (Play listing icon)
│   └── feature-graphic-1024x500.png# 1024x500, 24-bit PNG, no alpha
├── screenshots/
│   └── phone/                      # 1080x1920 gameplay captures, 9:16
│       ├── 01-scale-toss.png
│       ├── 02-tilted-scales.png
│       ├── 03-midgame.png
│       └── 04-early-game.png
├── listing/
│   └── en-US/
│       ├── title.txt               # 26 / 30 characters
│       ├── short_description.txt   # 64 / 80 characters
│       ├── full_description.txt    # 1751 / 4000 characters
│       └── release_notes.txt       # < 500 characters
└── tools/
    ├── generate_graphics.py        # icon, feature graphic, legacy launcher mipmaps
    └── verify_assets.py            # checks Play's dimension/format/limit rules
```

## Regenerating and verifying

```bash
python3 store/tools/generate_graphics.py   # redraws all store graphics + res/mipmap-* PNGs
python3 store/tools/verify_assets.py       # 68 checks; exits non-zero on any violation
```

`generate_graphics.py` needs Pillow and the Ubuntu fonts (`fonts-ubuntu`) and is fully deterministic —
rerunning it reproduces the checked-in files. Colours are the in-game marble palette from
`GameView.kt` (red/blue/yellow/green/purple/orange on `#10131A`).

## Asset notes

**App icon.** A balance scale with three marbles on the heavy pan and one on the light pan, plus two
marbles tossed off the light pan. Art stays inside the central area so Play's rounded-corner mask does
not clip it. The 512 icon is a separate composition from the launcher icon on purpose: a launcher icon
has to read at 48 dp, the listing icon at 512 px.

**Legacy launcher icons.** `app/src/main/res/mipmap-anydpi-v26/` only covers API 26+. The generated
`mipmap-{m,h,xh,xxh,xxx}hdpi/ic_launcher*.png` files give API 24–25 devices a real icon instead of a
blank one (minSdk is 24).

**Screenshots.** Captured from the real game running on an Android 14 emulator at 1080x1920 (9:16),
not mock-ups. Two come from one long run (four-digit score, all six colours, an 11-of-12-high column
and marbles caught in mid-air) and two from a second run so the boards are visibly different.
1080x1920 satisfies Play's 320–3840 px range and 2:1 aspect limit, and the files are 24-bit PNG
because Play rejects screenshots with an alpha channel.

The in-game button in these captures still reads LEADERBOARD, from before the online leaderboard was
replaced by the on-device high score list. Re-capture the four screenshots if you want the current
HIGH SCORES button in the listing.

**Title.** The launcher label is `Swing` (short, fits under the icon), while the store title is
`Swing: Marble Scale Puzzle` so the game is searchable. Play allows a descriptive store title as long
as it is not misleading; change `listing/en-US/title.txt` (max 30 chars) and
`app/src/main/res/values/strings.xml` together if you prefer a different name. Avoid using the name of
an existing arcade game in the title.

## Before you can publish

Assets are ready, but these are code/console steps that cannot live in this repo:

1. **App bundle.** `./gradlew bundleRelease` produces a signed AAB for `com.edens.marblemaster`
   (versionCode 3 / versionName 1.1), copied to `dist/Swing-1.1-v3.aab` with a SHA-256 file. Signing
   uses the upload key in `keystore/swing-upload.jks`, configured through `keystore.properties`; both
   are gitignored and must be backed up. The key's SHA-1 (also printed by
   `keytool -printcert -jarfile dist/Swing-1.1-v3.aab`) is
   `87:78:33:EA:A5:6F:C7:21:F0:39:44:0E:EB:CA:8E:03:1B:AB:BB:61`.
2. **Store listing.** Upload the graphics above, paste the four text files, add the phone screenshots.
3. **Privacy policy.** `docs/privacy-policy.html` is a self-contained page covering exactly what the
   game does: high scores on the device, no network access, no ads/analytics/trackers.
   Publish it with GitHub Pages (Settings → Pages → Deploy from a branch: `main`, folder `/docs`),
   which serves it at
   `https://peterede.github.io/MarbleMaster/privacy-policy.html`. Paste that URL into Play Console →
   App content → Privacy policy. The page is static: no scripts, cookies, or tracking pixels.
4. **Data safety + content rating.** Declare that the app collects no data, uses no advertising ID,
   and requests no permissions. Fill in the content rating questionnaire (no ads, no purchases, no
   user-generated content) and select the target audience.
5. **Publish to a testing track first**, then install from Play and play a few runs to verify the high
   score list end-to-end (finish a game, open HIGH SCORES, restart, confirm the list persists).

## Optional extras (not required for listing)

- 7" and 10" tablet screenshots (Play accepts them in separate slots; the game is portrait-only, so
  re-capture on a tablet-sized viewport).
- A promo video (YouTube URL).
- Localised listing text in `listing/<locale>/` once the English copy is final.
