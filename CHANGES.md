# Changes in this version

## New

- **Collections tab** (between Workshop and Lists). Every Workshop collection you download is kept as one unit. Each one can be applied, enabled, updated or deleted (*Remove only*, or *Delete mods too*, which never deletes a mod that another collection has). You can also show its mods, open it on Steam, run **Update all**, or add a collection by its link with *Add collection*. The records are copied to `Download/RimpyDroid/Collections`, like the lists. Lists and Settings moved one place right in the bottom bar.
- **Lists that only have names.** Name-only lines (bullets, numbers, `[x]`, comma-separated names, names next to package ids) are kept, matched to installed mods and looked up on the Workshop. A title counts when it has the same words (tags like `[1.6]` are ignored) or the wanted words plus at most 3 more. A title that lacks a word is never taken. "Download N missing, then apply" now appears for such lists, and names that were not found are listed.
- **Real Liquid glass** (*Settings → Appearance → Style*), now built on the lens of [Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android) (MIT, see `THIRD_PARTY_NOTICES.md`): cards, panels, buttons, chips, text fields, dialogs and the snackbar are panes of glass that refract and blur the backdrop behind them, split the colours at the edge and carry a lit rim. The accent colours the glass of the main button and of a chosen chip. This needs **Android 13 or later**; older phones keep the earlier translucent look. The backdrop stands still while the lens is on. **Classic** stays the default.
- **DLC.** With the RimpyDroid folder the game is not visible to the app, so you say which DLC you own: all of them are ticked and you only untick the ones you do not have (in the welcome guide, and later in *Settings*). The ticked ones are added to the load order and to `ModsConfig.xml` (`activeMods` and `knownExpansions`); the ones you leave unticked are known but not activated. An update from an older version asks once. A RimWorld folder needs none of this: its DLC are read from the game.
- **A third folder choice,** "RimpyDroid folder somewhere else": pick a place and the app makes `RimpyDroid` inside it.
- **`.nomedia`** in the RimpyDroid folder, so mod previews don't show up in the gallery.
- **Translations:** 616 texts in 15 languages. Plurals are whole sentences, so every language can word them its own way, and the help texts quote the translated button names.
- Up to 8 mods can download at the same time (was 5; the default is still 5).
- RimWorld 1.7 is offered in the Workshop filter and in Settings.

## Improved

- **Core and DLC in the RimpyDroid folder.** The app cannot see the game there, so it no longer reports "Core is not in the active list" or a mod that needs a DLC. A fresh list gets no Core stand-in, *Reset* becomes *Clear the load order*, imported lists neither activate nor report Core and DLC entries, and the mod details page shows "Part of the game" instead of a red "Missing".
- **Workshop search.** It still works when Steam's page changes its markup. Collections are searched without a version tag, and if a tag hides everything the search is repeated without it and says so. The search bar stays above the results, keeps its text, has a clear button, and old results are cleared when a new search starts. A search that finds nothing is written to the log.
- **Liquid glass pages.** Pages (Help, mod details, Problems, Log, Backups, folder picker, Steam browser) are opaque and no longer show the tab behind them. Menus stay solid, the whitish bar behind 3-button navigation is gone, and on Android 12+ the app behind a dialog is blurred when the phone allows it.

## Fixed

- **Harmony is never deleted with a collection.** *Delete mods too* now leaves Harmony installed, because most other mods cannot work without it. The dialog says so, and the message afterwards says Harmony was kept. Delete it from its own page if you really want it gone.
- **Workshop version filter.** Choosing 1.7 no longer shows mods made only for 1.6 or 1.4. Steam's own tag filter is not trusted: every result is checked against the mod's version tags, several pages are read to fill the list, and when nothing matches the list says so and offers *Show all versions*. A mod that is tagged for several versions, 1.7 among them, still appears.
- **Accent colour name.** With the *White* or *Black* theme the accent is now called *White* or *Black* (it stayed "Black"), and the names and descriptions are translated.
- **Arabic *fathatan*.** The tanwin (ً) is on the last letter, the alef: تلقائياً, not تلقائيًا. A test checks every Arabic text.
- **`ModsConfig.xml` that RimWorld rejected.** The file is now always written with Core, in the right place (after any mod that asks to load before it, such as Harmony), keeps the game's own `<version>` and `<knownExpansions>`, and is read back after writing. A file that does not read back as written is reported instead of trusted.
- Importing a huge list file ran out of memory. Reading is now bounded.
- Two list files with the same name crashed the Lists screen. Names are unique now.
- Opening the in-app browser crashed on phones without Android System WebView. It now says so and goes back.
- A download service that could not start in the foreground took the app down ten seconds later. It now stops cleanly.
- The sorter's tie-break followed the phone's digits (for example in Arabic). It no longer depends on the language.
- Sharing a very large text could throw an error.
- One slow mod could stop the library scan. Each mod now gets at most 30 seconds, and a folder that cannot be read is listed as unreadable.
- A mod's link opens only for http/https, and a missing handler no longer throws.

## Security

- XML with its own entity definitions is refused, and the check now covers the exact text the parser gets.
- Reading `About.xml`, `ModsConfig.xml` and imported lists no longer slows down on files with many unclosed tags (a 256 KB `About.xml` went from 63 s to 70 ms).
- Lists copied back from `Download/RimpyDroid` are limited to 100 files and 16 MB in one go.
