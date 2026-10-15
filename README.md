# Wild Frontier (와와 for Kakao) — Java port and format notes

A ground-up Java re-implementation of the Android game **Wild Frontier** (`wild.apk`,
`com.appstree.wawa`), built by reverse engineering its assets and its ARM library
`lib/armeabi/libwawa.so`. Nothing here is copied from a decompiler's Java output; the game
logic is re-derived from the native code, and every format is decoded from the shipped files.

Screenshots and measurements in these notes come from running the original in an Android-x86
4.4 VM and comparing it with this port, so claims are checkable rather than guessed.

## What works

- **Ground**: local area maps (`eng/map/*.mpk`) drawn from the 256×64 tile atlases
  (`img/tile00..13.png` + `pal/tileNN.png`) exactly as `DrawTile` indexes them.
- **Monsters**: spawned from the shipped `.mbk` records at their real cells, with the real
  Korean names (211 of them) and the real HP / attack / critical values.
- **World map, grid movement, turn-based combat, save/load**: playable Swing slice.

## What is not finished

- **Animation frame selection.** `CalcMonsAnim` picks a frame with
  `frame = baseFrame + step`, where `baseFrame` comes from a per-kind direction table. Those 53
  tables are extracted (`docs/dirtables.json`) and the `kind → dat` binding is proven for one
  kind, but how a chain entry's `(A, B)` becomes the drawn frame index is still open. The port
  therefore picks a standing pose by heuristic (dominant frame size, multi-colour, most opaque).
- **Monster level curve.** `hp = levelHp[level] * idHp[id] * 5 / 100`; only the id row is applied,
  because the hero's own damage model has not been derived yet and the level curve multiplies HP
  by roughly 25.
- **Palette variants** (`LoadSpritePal`), `.mbk` object/portal tables, quest and event scripts.

## Requirements

- JDK 17 or newer (the port uses records and switch expressions).
- An extracted copy of the original APK's `assets/` tree, which is **not** redistributed here.
  The tools take its path as an argument, e.g. `assets/assets` for the APK's `assets/` directory.
  `lib/armeabi/libwawa.so` sits next to it and is read for the monster tables.

## Build and run

```bash
javac -d out $(find src -name '*.java')

# playable slice
java -cp out wawa.Game <path-to-assets>

# load every shipped map, area and monster table and report what came back
java -cp out wawa.CheckMapsRun <path-to-assets>

# render every map of every area
java -cp out wawa.ExtractAll <path-to-assets> <output-dir>
```

`wawa.Game` supports `--selftest` to drive scripted input and write `out/selftest.png`, so the
render can be checked without a GUI.

## Layout

| path | contents |
| --- | --- |
| `src/main/java/wawa/Game.java` | the playable slice |
| `src/main/java/wawa/{MapData,TileAtlas,Tiles}.java` | ground pipeline |
| `src/main/java/wawa/{MonsterTable,MonsterData,MonsterStats,MonsterSprite}.java` | monsters |
| `src/main/java/wawa/{Gsac,RenderAtlas,ElfImage,ResourceFile}.java` | asset decoding |
| `src/main/java/wawa/{CheckMaps,CheckMapsRun,ExtractAll,MatchSprite,FindBySize}.java` | tools |
| `docs/FORMATS.md` | every asset format, with the function that proves it |
| `docs/ORIGINAL-VM.md` | how to run the original locally, including the Dalvik patch it needs |
| `docs/dirtables.json` | the 53 extracted per-kind animation tables |
| `PORT-BREAKDOWN.md` | what the APK is made of and the porting order |

## Notes on the original

The game is 32-bit ARM only, so it needs a translating runtime to run on x86.
It also aborts after about 17 seconds on Android 4.4 (Dalvik) because it leaks a JNI pinned
array reference every frame and the pin table caps at 1024; `docs/ORIGINAL-VM.md` records the
one-constant patch that lifts the cap.
