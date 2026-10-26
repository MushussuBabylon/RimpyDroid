<p align="center"><img src="docs/logo.jpg" width="220" alt="RimpyDroid"></p>

# RimpyDroid

A mod manager for **RimWorld on Android**, in the spirit of RimPy and RimSort. It is made for people who run the
Windows version of the game through **Winlator** or **Gamehub** or a similar launcher, and who want to
**download and update Workshop mods straight from the phone**.

No Steam client, no Steam account and no PC are needed: the app talks to Steam itself using an anonymous login.

## What it does

| | |
|---|---|
| **Workshop** | Search the Steam Workshop (sort, collections, game version), or paste Workshop links / collection links. One tap downloads a mod; a collection downloads all of its mods. Downloads are queued and run one mod after another (each over several parallel connections), in the background, with a notification. |
| **Updates** | Checks which of your Workshop mods changed on Steam and updates them. Only the changed parts of a mod are transferred. |
| **Load order** | Enable / disable mods, move them (to top, up, down, to bottom, before / after another mod), or tap **Sort**. Sorting follows what RimPy and RimSort do: dependencies first, then the mods' own `loadBefore` / `loadAfter`, then the RimSort community rules. Core and the DLCs stay at the top. |
| **Problems** | Finds missing or disabled dependencies, wrong order, mods that are incompatible with each other, mods made for another game version, duplicate folders and entries for mods that are no longer installed. Most problems have a one-tap fix, and "Fix automatically" does the safe ones in one go. |
| **Lists** | Save the current load order as a named list (profile), apply it later, share it, or import one (RimPy / RimSort lists, `ModsConfig.xml`, a plain list of Workshop links). A list can download the mods that are missing and apply itself afterwards. |
| **Safety** | Every time the app overwrites `ModsConfig.xml` it keeps a backup next to it (last 8, restorable from the app). The app only touches the Mods folder, `ModsConfig.xml` and those backups. |
| **Look** | White and black. *Settings → Appearance*: white, black, or follow the phone's dark mode. The black can be **any colour** (ready-made colours, sliders, or a hex code such as `#1565C0`); the app keeps the text readable whatever you pick. |

## Good to know

- **Steam must be reachable.** Depending on country or network you may need a VPN or another DNS for the Steam
  servers. The app logs in to Steam anonymously, asks it where the mod's files are, and then fetches the files
  from Steam's download servers over plain HTTPS. If the first connection method does not get through it tries
  two others by itself (WebSocket only, then TCP only). Downloads that stall or fail are retried; if one still
  fails, the log (*Settings → View log → Share*) shows what happened.
- **Mods you already have** (copied from a PC, or from the Steam client) are found and sorted like any other.
  They have no download record, so the app offers to "Fetch" them once to enable update checks; files that are
  identical are not downloaded again.
- **Lists from other tools** can only download mods that carry a Workshop id. Entries without one are reported
  as missing and have to be installed by hand.
- **Mods without a `packageId`** in `About.xml` cannot be put into the load order (RimWorld itself needs the id).
- **Anonymous Steam login** works for the public Workshop. Items that are hidden, friends-only or removed cannot
  be downloaded.
- **Fallback if on-device downloading does not work for you:** Winlator can run SteamCMD
  (`steamcmd +login anonymous +workshop_download_item 294100 <id> +quit`). Mods land in
  `steamapps/workshop/content/294100/<id>`; copy the folder into `Mods`. The app can then sort and manage them.

## Credits

- Sorting rules: the [RimSort Community Rules Database](https://github.com/RimSort/Community-Rules-Database),
  downloaded when you tap *Update* in Settings (it is not bundled).
- Steam access: [JavaSteam](https://github.com/Longi94/JavaSteam) (MIT).
- The way Workshop items are downloaded (anonymous login, then a manual SteamPipe download of the manifest and
  its chunks) follows the mechanism of `SteamAnonModDownloader` from
  [RimDroid](https://github.com/udarmolota/rimdroid) by udarmolota (MIT, Copyright (c) 2026 udarmolota),
  ported to Kotlin. The notice and the licence text are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
- Inspired by RimPy and RimSort. Not affiliated with Ludeon Studios, Valve, RimPy, RimSort or RimDroid.
- RimWorld is a trademark of Ludeon Studios.