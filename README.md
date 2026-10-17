# Wild Frontier (와와 for Kakao) — Java port

A ground-up Java re-implementation of the Android game **Wild Frontier** (`wild.apk`,
`com.appstree.wawa`), built by reverse engineering its assets and its ARM library
`lib/armeabi/libwawa.so`. Nothing here is copied from a decompiler's Java output; the game
logic is re-derived from the native code, and every format is decoded from the shipped files.

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
- **World map, grid movement, real-time melee, save/load**: the attack key swings the original's
  combo (acts 9, 0xa, 0xf) on its weapon timing tables, hitting every monster whose hurtbox meets
  the swing; struck monsters flinch and are pushed, dead ones fly off and lie as corpses
  (`Fight`, `GameCore`), with the original's damage numbers, combo counter and target HP bar.
- **Monster AI** (`CalcMonsAct`): hiding, standing and patrolling inside the spawn window, sight
  rectangles with the "!" bubble, breadth-first routing to the attack side, attacks that roll hit,
  shield block, crit and flinch in the original's order, fleeing at low HP; the hero flinches
  when struck and its armour wears.
- **Harvesting (갈무리)**: holding the attack key over a corpse plays act 2 with its gauge, one
  gather every 12 frames per size class, rolling materials (`g_anMonsDrop`/`g_anMonsItem`), runes
  and recipes as GetItemHeroGather does; kills give experience only.
- **Quests** (`QuestBook`, `Quests`, `QuestMenu`): the 265 quest definitions of `dat/quesn`, the
  hero's seven slots, GMenu 100 offers (main quests confirm, sub and free quests accept or decline),
  kill, condition, item and time goals, quest drops rolled during 갈무리, turn-in with the reward
  phase, and the quest-status conditions the event scripts test. The menu's QUEST tab lists the
  slots with their summaries, details and delete; the town quest boards (GOrder 8) offer today's
  free quests; NPCs show the offer / report / in-progress mark AnalysisQuest works out.
- **Terrain**: each map's collision grid is built as LoadGameBlock builds it, from the tile sets'
  `g_anTileBlock` tables in `libwawa.so` (`BlockGrid`). Deep water, cliffs, cave walls and lava
  stop the hero; shallow water and bridges do not. Walking into a blocking tile steps aside to
  the side that opens within two cells, still facing the same way, as NewCollPath does. Monsters
  stop at the same grid through their id's `g_anMonsBlock` mask (CollMonsPath): most at the
  hero's terrain, 33 kinds cross deep water and 4 cross everything.
- **Event scripts**: `eve/*.evk` phases run as the original interprets them (`EventRunner`): a new
  game plays t01's opening with its captions, dialogue (portraits, names, typing; Enter advances,
  F toggles fast mode), NPC units, hero moves, camera, fades and emote bubbles. NPC events start
  with Enter next to the NPC. Script variables are saved with the slot.

## What is not finished

- **Inventory and equipment.** Monsters get InitMonsAbility's stats at their real level and the hero
  follows InitHero / InitHeroState / InitHeroLevelUp (`Hero`, `Combat`). The hero record's items are
  ported as the original keeps them (`Inventory`: 50 equipment instances and 305 stack slots, gold),
  with AddItem / DelItem / SortItem / GetItemCount and the event item commands; worn gear sets
  attack, defence and the hero's looks. The menu's ITEM tab (`ItemMenu`, opened with `I` or
  Backspace) lists, equips, unequips, uses and discards them on the original sheets, and the shops
  scripts open (`ShopMenu`, GOrder 0xd/0xe modes 0, 1, 3 and 6) buy and sell at the original's
  prices. The other menu tabs besides QUEST (STATUS, SKILL, PET, LOG, SYSTEM), the gamble and cash
  shops, gear options, runes, sockets, cooldowns and durability loss are not ported yet.
- **Event script gaps.** Buffs, sound, zoom, map exit, area and object triggers, and effect/attack
  actions are not ported, nor the gamble and cash shops; a script that reaches one logs `[미포팅]`
  and carries on.
  Boss/regen map exits do open their warning (GMenu 0x74) and enter on its Enter button.
- **Animation playback.** Poses for any state and step are composed (`MonsterAnim.pose`,
  `HeroAnim.pose`). Monsters animate every normal state (hide, stand, walk, alert, chase, attack,
  flinch, flee, death, corpse); the hero draws its stand, walk, sit, melee and flinch acts
  (skills and riding are not ported).
- **Palette variants** (`LoadSpritePal`), `.mbk` object/portal tables.

## Requirements

- JDK 17 or newer (the port uses records and switch expressions; the browser build targets 17).
  The browser build uses the bundled Gradle wrapper (8.14), which needs JAVA_HOME on a JDK 17–24.
- Your own copy of the original `wild.apk`. This repository and the site ship no part of it (no
  APK, assets, library or decompiled code). `wawa.Game` reads the APK in place (assets and
  `lib/armeabi/libwawa.so`); the tools take an extracted `assets/` directory, with
  `lib/armeabi/libwawa.so` next to it.

## Build and run

```bash
javac -encoding UTF-8 -d out $(find src -name '*.java')
cp -r src/main/resources/* out/    # the text font

# playable slice, straight from the APK or from an extracted assets/ directory
java -cp out wawa.Game <path-to-your-wild.apk>

# load every shipped map, area and monster table and report what came back
java -cp out wawa.CheckMapsRun <path-to-assets>

# render every map of every area
java -cp out wawa.ExtractAll <path-to-assets> <output-dir>
```

`wawa.Game` supports `--selftest` to drive scripted input through the opening, the slot screen, a
new game with t01's opening scene and a few fights, writing `out/logo.png`, `out/title*.png`,
`out/slots.png`, `out/newgame.png`, `out/intro*.png`, `out/selftest.png`, `out/menu-item.png`,
`out/menu-popup.png`, `out/shop-*.png`, `out/melee.png`, `out/corpse.png` and `out/slots-after.png`,
so the render can be checked without a GUI.

## Play in the browser

GitHub Pages serves `web/index.html`, `wawa.js` and the font (`.github/workflows/pages.yml` builds
it on every push to `main`); it does not serve the APK. `wawa.js` is the game compiled ahead of time
to JavaScript by TeaVM 0.14.1 from `wawa.WebMain` (`web/src`, the only source that needs TeaVM's
browser API) and everything it reaches; desktop-only classes (Swing, ImageIO, the tools) are not
translated. The player picks their own `wild.apk` once; the page keeps it in the browser's
IndexedDB (never uploading it), unpacks `assets/` and `libwawa.so` in memory and draws onto a
416×312 canvas scaled to the window. A file that is not Wild Frontier's APK is forgotten and the
page asks again. Saves go to the browser's localStorage (`wawa-*` keys).

Text is not drawn into the game's pixels: `Gfx` records each character in the cell the original's
bitmap font would use, and the screen draws it at display resolution with GalmuriMono11 (by Lee
Minseo, SIL Open Font License 1.1, `src/main/resources/wawa/Galmuri-LICENSE.txt`), subset to KS X 1001
Hangul, symbols and ASCII. The original's 480×320 screens are scaled to the canvas, and their bitmap
glyphs did not survive that.

To try it locally:

```bash
sh ./gradlew generateJavaScript
cp build/generated/teavm/js/wawa.js src/main/resources/wawa/GalmuriMono11.ttf web/
npx http-server web    # or any static server; the page asks for your wild.apk on first load
```

## Layout

| path | contents |
| --- | --- |
| `src/main/java/wawa/Game.java` | the playable slice |
| `src/main/java/wawa/{TitleScreen,SlotScreen}.java` | logo/title opening, slot select scene and new-game panel |
| `src/main/java/wawa/{MapData,TileAtlas,Tiles,BlockGrid}.java` | ground pipeline and its collision grid |
| `src/main/java/wawa/{MonsterTable,MonsterData,MonsterStats,MonsterSprite,MonsterAnim}.java` | monsters |
| `src/main/java/wawa/{PartSprites,HeroAnim}.java` | dat part composition shared by monsters and the hero; hero outfit layers |
| `src/main/java/wawa/{EventScript,EventRunner,TalkBox}.java` | event tables and length rules, the phase interpreter, the dialogue box |
| `src/main/java/wawa/{QuestBook,Quests,QuestMenu}.java` | quest definitions and reward phases, the hero's quest slots, goal checks and quest drops, the QUEST tab and quest board |
| `src/main/java/wawa/{Hero,Item,Inventory,Combat,Fight}.java` | hero record, item tables, the hero's items, hit resolution, melee tables and hit geometry |
| `src/main/java/wawa/{Gsac,RenderAtlas,ElfImage,ResourceFile}.java` | asset decoding |
| `src/main/java/wawa/{CheckMaps,CheckMapsRun,ExtractAll,MatchSprite,FindBySize}.java` | tools |
| `web/index.html`, `web/src/wawa/WebMain.java`, `build.gradle.kts`, `.github/workflows/pages.yml` | browser page, its TeaVM entry point, the TeaVM build and the Pages workflow |
