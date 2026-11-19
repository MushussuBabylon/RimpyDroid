package com.rimpydroid.manager.ui

/** One line of the help: a name (as written on the screen, or a short description) and what it does. */
internal class HelpTopic(val id: Int, val title: String, val body: String, val group: String = "")

/** A title that names buttons ("Enable / Disable"): each name is translated like the button it names. */
private fun lbl(names: String): String = names.split(" / ").joinToString(" / ") { tr(it) }

/**
 * Every tab, button, menu item and setting of the app, group by group. Names that the screen itself writes in English
 * (the screens that are not translated yet) are written as they appear there, so that they can be found on the screen.
 */
internal fun helpReference(): List<HelpTopic> {
    val out = ArrayList<HelpTopic>()
    var next = 100
    fun group(name: String, vararg entries: Pair<String, String>) {
        for ((title, body) in entries) out.add(HelpTopic(next++, title, body, name))
    }

    group(
        tr("The four tabs"),
        tr("Mods") to tr("Your installed mods and the load order. Switch, search, sort, fix."),
        tr("Workshop") to tr("Find mods on the Steam Workshop and download them."),
        tr("Lists") to tr("Saved load orders to apply, share or import."),
        tr("Settings") to tr("Folders, downloads, language, look and maintenance."),
    )
    group(
        tr("Mods tab: top bar"),
        tr("Save") to tr("Writes ModsConfig.xml. Appears only when automatic saving is off."),
        tr("Sort") to tr("Orders the active mods using their own rules and the community rules."),
        tr("More") to tr("Opens the menu of actions below."),
        tr("Problems ({0})", "N") to tr("Shows missing mods, wrong order and incompatibilities, with fixes."),
        tr("Check for updates") to tr("Asks Steam for newer versions. Update all then downloads them (changed files only)."),
        tr("Fix problems automatically") to tr("Enables needed mods, removes gone ones and sorts the list."),
        tr("Clean up mods…") to tr("Delete or disable incompatible, inactive and duplicate mods, after a preview."),
        tr("Reload from disk") to tr("Reads the Mods folder and ModsConfig.xml again."),
        tr("Reset to Core and DLC") to tr("Starts the load order again with only the game's own content."),
        tr("ModsConfig.xml backups") to tr("Opens saved copies of ModsConfig.xml to go back to."),
    )
    group(
        tr("Mods tab: the list"),
        lbl("Active / Inactive") to tr("Active: mods in the load order, numbered. Inactive: installed mods that are off."),
        lbl("Search name, author or package id") to tr("Filters the list as you type."),
        tr("Mod row") to tr("Tap to open the mod's page. The number is its place in the load order."),
        tr("Switch and Remove") to tr("The switch turns a mod on or off. Remove drops an entry of a mod that is gone."),
        tr("Tags and warning sign") to tr("Labels show where a mod comes from and what needs attention. The triangle marks a problem."),
        tr("Error and warning counts") to tr("Tap them to open the Problems page."),
        tr("Notices at the top") to tr("Banners with one-tap buttons such as Enable, Update all, Fetch (records versions for update checks) and Save."),
    )
    group(
        tr("A mod's page"),
        lbl("Enable / Disable") to tr("Switches the mod on or off."),
        lbl("Update / Re-download") to tr("Update gets the newer version. Re-download repairs the mod."),
        lbl("Open on Steam") to tr("Opens the mod's Workshop page."),
        lbl("To top / Up / Down / To bottom / Move after… / Move before…") to tr("Move the mod in the load order (enabled mods only)."),
        lbl("Problems / Requires") to tr("What is wrong with the mod, and the mods it needs: Enable or Download them."),
        lbl("Details") to tr("Package id, versions, folder, size, link and description."),
        lbl("Delete mod folder…") to tr("Deletes the mod from your phone after you confirm. This cannot be undone."),
    )
    group(
        tr("Problems page"),
        lbl("Fix automatically") to tr("Applies all safe fixes at once. Download missing fetches mods that have a Workshop link."),
        tr("Buttons on a problem") to tr("Each problem has one button: Enable, Sort load order, Remove from list, Disable or Download."),
        lbl("Errors / Warnings / Notes") to tr("Errors need fixing, warnings are likely trouble, notes are hints."),
        lbl("Sorting notes") to tr("Shown when mods' rules contradict each other. The sorter followed the mods' own rules."),
    )
    group(
        tr("Clean up mods"),
        lbl("Incompatible with your game version") to tr("Mods made only for other RimWorld versions. Delete removes them, Disable switches them off."),
        lbl("Inactive mods") to tr("Installed but not in the load order. Core and DLC are never deleted."),
        lbl("Duplicate copies") to tr("Extra copies in other folders. The copy the app uses stays."),
    )
    group(
        tr("Workshop tab"),
        lbl("Search mods by name, or paste Workshop links") to tr("Type a mod name, or paste Workshop or collection links, then tap the magnifier."),
        tr("Browse") to tr("Opens the Steam Workshop inside the app like a browser, with Download buttons."),
        tr("Refresh") to tr("Runs the search again."),
        lbl("Sort / Collections / Any version") to tr("Sort chooses the order. Collections shows collections. Version buttons show mods for that RimWorld version."),
        lbl("Download / Update / Re-download / Installed") to tr("Download gets a mod, Update a newer version, Re-download repairs. Installed: already here."),
        lbl("Show mods / Steam page / Cancel / Load more") to tr("Show mods opens a collection, Steam page opens Steam, Cancel stops a download, Load more adds results."),
        lbl("Downloads") to tr("Cancel all stops everything, Retry failed tries again, Clear finished tidies the list."),
        tr("Progress bar above the tabs") to tr("Shows all downloads. Tap it to open the Workshop tab."),
    )
    group(
        tr("Browse page"),
        tr("Popular") + ", " + tr("Newest") + ", " + tr("Collections") to tr("Jump to those lists of the Workshop."),
        tr("Download") to tr("Under every mod and on a mod's page: gets that mod, or a whole collection."),
        tr("Back") + ", " + tr("Refresh") to tr("Back goes to the previous page, then closes the browser. Refresh reloads the page."),
    )
    group(
        tr("Lists tab"),
        lbl("Save current…") to tr("Saves the load order as a named list."),
        lbl("Import…") to tr("Pastes or opens a list from RimPy, RimSort, this app, ModsConfig.xml or Workshop links."),
        tr("A saved list") to tr("Tap it to see its actions: Apply list, Download missing then apply, Replace with current order, Share, Copy, Rename, Delete."),
        lbl("Apply list") to tr("Switches on the list's installed mods in its order. You can undo it."),
        lbl("Share / Copy / Rename / Delete") to tr("Share and Copy send the list out, Rename renames it, Delete removes it but not your mods."),
    )
    group(
        tr("ModsConfig.xml backups"),
        lbl("Load / Delete") to tr("Load takes that backup's load order (you can undo it). Delete removes the backup."),
    )
    group(
        tr("Settings"),
        tr("File access") to tr("RimpyDroid needs All files access to read and write your mods and load order."),
        tr("Where your files live") to tr("Settings, Storage: use the RimpyDroid folder (Download/RimpyDroid), your RimWorld folder, or a RimpyDroid folder made somewhere else. You can switch at any time."),
        tr("RimWorld folder") + ", " + tr("Mods folder") + ", ModsConfig.xml" to tr("Choose… sets one by hand, Clear goes back to the default."),
        tr("RimWorld version") to tr("Used to spot mods made for another version. Not needed when a RimWorld folder is chosen."),
        tr("Backups and lists") to tr("Lists are copied to Download/RimpyDroid/Lists. ModsConfig backups sit next to ModsConfig.xml. Both survive removing the app."),
        tr("Save changes automatically") to tr("A moment after every change. A backup of the old file is kept."),
        tr("Keep the current order when sorting") to tr("Mods without a rule stay where they are instead of being sorted by name."),
        tr("Check for mod updates on start") to tr("Asks Steam which of your Workshop mods changed."),
        tr("Mods downloaded at the same time") + " / " + tr("Download connections") to tr("Mods at once: 1 to 5. Connections: 2 to 16. Use fewer on weak Wi-Fi. Reset to defaults gives 5 and 16."),
        tr("Language") to tr("Chooses the language of the app. Automatic follows the phone."),
        tr("White") + ", " + tr("Black") + ", " + tr("Follow system") to tr("Chooses the theme: white, black or the phone's setting."),
        tr("Accent colour") to tr("White and black. The black can be any colour."),
        tr("Community sorting rules") to tr("From the RimSort community database. Tells the sorter which mods need a special place."),
        tr("Rescan mods") to tr("Reads the Mods folder and ModsConfig.xml again."),
        tr("View log") + ", " + tr("Last crash") to tr("Copy or Share the log or crash report when you ask for help. Clear and Delete remove them."),
        tr("The (?) button") to tr("Opens this help. Show the welcome guide again replays the first steps."),
    )
    group(
        tr("Choosing a folder"),
        lbl("Use this folder") to tr("Picks the folder you are in. Tap a folder to open it, Up to go back."),
        lbl("Find automatically") to tr("Searches your phone for RimWorld, Mods or ModsConfig.xml. Use picks a result."),
    )
    group(
        tr("Welcome guide"),
        tr("Skip") + ", " + tr("Back") + ", " + tr("Next") + ", " + tr("Start") to tr("Skip closes the guide, Back and Next move between steps, Start finishes it."),
    )
    return out
}
