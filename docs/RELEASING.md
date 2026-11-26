# Releasing

A checklist so a release is the same every time. The version lives in one place, `build.gradle` (`version = ...`); `fabric.mod.json` and the jar name take it from there.

0. The licence is MIT (`LICENSE`); keep `fabric.mod.json`, `README.md`, `THIRD-PARTY.md` and the wiki credits saying so.
1. **Decide the version** and edit `build.gradle`, then add a section to `CHANGELOG.md` (what players and server owners will notice, known limits).
2. **Run everything locally:**
   ```
   gradlew build                              # all unit tests and the jar
   python -I tools/build_resourcepack.py      # pack, item art, icon art (art/icons), mod icon; prints the pack sha1
   python -I tools/build_guide.py --check     # the guide and wiki handoff match the code (drop --check to rewrite them)
   python -I tools/verify_artifact.py         # the jar nests domain, store and SQLite; Cobblemon pin recorded
   ```
   Commit whatever the generators rewrote; CI fails if the committed copies differ.
3. **Live pass:** run the checklist in `docs/LIVE-TEST-CHECKLIST.md` on a real server and client, on every Cobblemon version the mod declares.
4. **Tag and push:** `git tag v<version>` and push the tag. CI builds the jar and uploads it as an artifact.
5. **Publish:** attach `ascensionlib/build/libs/AscensionLib-<version>.jar` and `build/AscensionLib-resourcepack.zip` (with its sha1 in the notes) to the release page, with the changelog section. Link `docs/INSTALL.md` and `docs/WIKI-HANDOFF.md`.
6. **After:** re-host the resource pack at the new hash on your own servers, and tell the companion mods (CobbleRaids, CobbleTowers, ascensionDungeons) the contract version if it changed (`ContractSignaturesTest` pins every method they reach).

## Compatibility notes to refresh each time

- Cobblemon versions the mod declares (`fabric.mod.json`, `README.md`, `INSTALL.md`, `tools/verify_artifact.py`).
- The screens are native Snobblemon GUI pages (SnobbleGUIOverhaul): its `tools/required_pages.txt` must list every page this mod opens, and `tools/sync_asc_guide.py` must be rerun there when `design/guide.json` changes.
- That the bundled Showdown inside Cobblemon is unchanged, or the simulator tests (`validation/showdown/ascension_fx_test.js --showdown-dir <unbundled Showdown>`) still pass.
