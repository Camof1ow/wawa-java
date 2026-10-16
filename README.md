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
  Korean names (211 of them) and the real HP / attack / critical values, drawn in their standing
  pose composed from `dat/NN.dat` parts exactly as `CalcMonsAnim` / `DrawMonsAnim` assemble them.
- **Opening**: the three logo screens (`DrawLogo`) and the title with its Play / Options / Help
  menu (`DrawTitle` / `KeyPressTitle`, `TitleScreen`); any key opens the menu, Play leads on.
- **Slot screen**: Play opens the three save slots around the camp in map `a13`, laid out
  from the original `clix_gorder` / `clix_gmenu` tables (`SlotScreen`). ←/→ picks a slot, Enter
  continues it or, on an empty slot, opens the new-game panel (name, class Warrior / Tanker / Scout
  with its start stats and summary) that starts in `t01`; ↑/↓ or Delete opens Continue / Delete.
  Each slot is saved as `wawa-slotN.properties`.
- **Hero**: drawn from `dat/20.dat` with its equipment layers (`ani_hero` / `con_hero` /
  `lnk_hero`, palette rows from `pal/<n>.png`) on the slot screen, the new-game preview (each
  class's outfit) and the field.
- **World map, grid movement, turn-based combat, save/load**: playable Swing slice.
- **Event scripts**: `eve/*.evk` phases run as the original interprets them (`EventRunner`): a new
  game plays t01's opening with its captions, dialogue (portraits, names, typing; Enter advances,
  F toggles fast mode), NPC units, hero moves, camera, fades and emote bubbles. NPC events start
  with Enter next to the NPC. Script variables are saved with the slot.

## What is not finished

- **Inventory and equipment.** Monsters get InitMonsAbility's stats at their real level and the hero
  follows InitHero / InitHeroState / InitHeroLevelUp (`Hero`, `Combat`). The hero holds item 297, the
  first weapon the t02 event script equips on a new game, because map exits are not ported and t02
  cannot be reached yet; there is no inventory, armour or equip screen.
- **Event script gaps.** Quests, buffs, sound, zoom, choices, the GOrder/GMenu screens, map exit,
  area and object triggers, and hero actions (`b3`) are not ported; a script that reaches one logs
  `[미포팅]` and carries on as `docs/FORMATS.md` §10 lists.
- **Animation playback.** Poses for any state and step are composed (`MonsterAnim.pose`,
  `HeroAnim.pose`); monsters only draw state 0, step 0, and the hero only its stand, walk and sit
  actions (attacks, skills and riding are not ported).
- **Palette variants** (`LoadSpritePal`), `.mbk` object/portal tables.

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

`wawa.Game` supports `--selftest` to drive scripted input through the slot screen, a new game and
a few fights, writing `out/slots.png`, `out/newgame.png`, `out/selftest.png` and
`out/slots-after.png`, so the render can be checked without a GUI.

## Layout

| path | contents |
| --- | --- |
| `src/main/java/wawa/Game.java` | the playable slice |
| `src/main/java/wawa/{TitleScreen,SlotScreen}.java` | logo/title opening, slot select scene and new-game panel |
| `src/main/java/wawa/{MapData,TileAtlas,Tiles}.java` | ground pipeline |
| `src/main/java/wawa/{MonsterTable,MonsterData,MonsterStats,MonsterSprite,MonsterAnim}.java` | monsters |
| `src/main/java/wawa/{PartSprites,HeroAnim}.java` | dat part composition shared by monsters and the hero; hero outfit layers |
| `src/main/java/wawa/{EventScript,EventRunner,TalkBox}.java` | event tables and length rules, the phase interpreter, the dialogue box |
| `src/main/java/wawa/{Hero,Item,Combat}.java` | hero record, item tables, hit resolution |
| `src/main/java/wawa/{Gsac,RenderAtlas,ElfImage,ResourceFile}.java` | asset decoding |
| `src/main/java/wawa/{CheckMaps,CheckMapsRun,ExtractAll,MatchSprite,FindBySize}.java` | tools |
| `docs/FORMATS.md` | every asset format, with the function that proves it |
| `docs/ORIGINAL-VM.md` | how to run the original locally, including the Dalvik patch it needs |
| `PORT-BREAKDOWN.md` | what the APK is made of and the porting order |

## Notes on the original

The game is 32-bit ARM only, so it needs a translating runtime to run on x86.
It also aborts after about 17 seconds on Android 4.4 (Dalvik) because it leaks a JNI pinned
array reference every frame and the pin table caps at 1024; `docs/ORIGINAL-VM.md` records the
one-constant patch that lifts the cap.
