Servux New Features (0.3.7+)
============================

## New Data Provider changes since Servux 0.1.0:
* `DataProviderToggles` in `servux.json` now can actually enable/disable data providers.  `servux.json` file is now usable (in 0.2.0 and lower; this was not possible).  So yes; you can technically disable the `servux_main` data provider; and then lose the ability to use those features, or fix it without replacing the config file.
* `servux_main` - Core Servux-based service that manages the `servux.json` file and the `/servux` command management.
    * Provides backend setting for `permission_level_easy_place` --> Adds the `Easy Place V3` server-side backed for Litematica and Tweakeroo.
      * It now has the `easy_place_validator_enabled` configuration. 
    * Provides backend setting for `default_language` & `debug_log`.
* `hud_data` - Provides MiniHUD with server side data for any misc, and Info Line / HUD data.  It can be activated by the `Generic` -> `hudDataSync` toggle.  It can provide:
  * Spawn Chunk Radius / Spawn Position.  Shared upon metadata handshake, or future changes to the spawn metadata; such as when someone changes the world spawn location.
  * Weather Info `share_weather_status` and related `update_interval` for tick rate limiting; and has a separate permission node.
  * World Seed `share_seed`.  Only shared upon Metadata handshake, and has a separate permission node.
  * (1.21.2+) ServerRecipeBook data dump (For Use with FurnaceXP Info Line); only sent upon request, and normally at server login after the metadata handshake.
  * TPS / Mob Cap loggers.  Needs to be enabled, but then they work similar to receiving the same data from Carpet.  This is meant to be a fallback when Carpet is not available.
* `entity_data` - Provides MiniHUD with entity/tile entity NBT information for various systems such as `inventoryPreview`, various Renderers, and various Info Lines.  It can be activated by `Generic` -> `entityDataSync`.
  * Provides backend setting for `nbtQueryOverride` where you can offer an alternative OP permission level for Vanilla `NbtQuery` packets.
  * The `nbt_allow_player_inventory` and the related `nbt_allow_player_ender_items` with their related permissions levels can control the personal inventory access for this data.
  * The `fix_allay_gathering` setting enables an override for testing Allay based item sorting while the game rule `mob_griefing` is enabled.
* `litematic_data` - Provides Litematica with entity/tile entity NBT information for use with `InfoOverlay`; and also provides Litematic saving and pasting services.  It can be activated by `Generic` -> `entityDataSync`.
  * Provides backend setting for `fix_rail_rotations`, `fix_stairs_mirror`, && `fix_chest_mirror`.
  * Litematic Paste operations has a separate permissions node.
  * Provides server side Schematic Verification via `/servux verify`, which is not bound by the client's render distance.  Verification is read-only: it never writes to the world, never generates terrain, and never stalls the server thread.
    * Mismatches are reported per category (`Missing`, `Extra`, `Wrong Block`, `Wrong State`, `Wrong Contents`), with clickable coordinates that suggest a teleport, so the results are usable from vanilla clients with no mod installed.
    * Chunks outside the client's render distance are loaded on demand, so a verification covers the whole build.  Loading is non-blocking and the chunks are loaded but not ticked -- no mob spawning, no redstone, no block or random ticks.  Chunks that have never been generated are reported, not generated, so inspecting a build never enlarges the world; `verify_generate_missing_chunks` opts into generating them.  New loads back off while the server's tick time is high (`verify_pause_mspt_threshold`).
    * `Wrong Contents` compares container inventories, which Litematica's client side verifier cannot do at all -- it only compares block states, so an empty chest counts as correct there.  Only checked where the block state already matches, so this count overlaps the correct-state count rather than adding to the other categories.
    * Can verify schematics already shared through Syncmatica without an upload, by reading its placement manifest from disk.  This is a read-only, unofficial interface and can be turned off with `verify_syncmatica_interop`.
    * Verify operations have a separate permissions node.
* `tweaks_data` - Provides Tweakeroo with entity/tile entity NBT information for `inventoryPreview`.  Can be expanded in the future to support more advanced Tweaks.  It can be activated by enabling `entityDataSync`.
  * Can provide the server side method for `stackable_shulkers` with the related `stackable_shulkers_count`, simillar to how Carpet can provide this.
  * This implementation also provides a lightweight `stackable_shulkers_fix` config for hoppers coded for Carpet by [KikuGie] under their [stackable-shulkers-fix] mod.
* _**NOTE**_:  All Data Providers also has their related `permission` configs for controlling OP level style permissions.  All Data Providers and permissions are also compatible with Luck Permissions API.
  * Example Luck Permissions API node: `servux.provider.entity_data.nbt_query_override`.

## `/servux` Command reference:
  * `reload` -- Reloads the config file, discarding the existing config in memory.
  * `save` -- Forces a save of the config file, discarding the existing file; and overwriting it with the configuration in memory.
  * `set` [setting] [value] -- Sets a configuration [value] for the [setting].
  * `info` [setting] -- Displays the current configuration for the [setting].
  * `list` [dataprovider] -- Lists all settings and their respective values.  Can be limited to a specific [dataprovider].
  * `search` [pattern] --- Lists all settings matching the search [pattern].
  * `verify list` -- Lists the schematic placements shared through Syncmatica that can be verified.
  * `verify start` [placement] -- Starts a server side verification of a shared [placement], given by display name or UUID.
  * `verify status` -- Shows the progress of the verifications currently running.
  * `verify cancel` [session] -- Cancels a running verification; defaults to your own.
  * `verify show` [category] [page] -- Lists the mismatches of one [category] from your latest verification.  Each coordinate can be clicked to auto-complete a teleport to it.
  * All config settings can be clicked upon to auto-complete a `set` command; after using `info`, `list` or `search`; similar to how the `/carpet` command works.
  * Available settings are modularized per their respective [dataprovider].
  * All `/servux` command text can be translated using the available i18n language files.  Currently only English `en_us` and Chinese (Traditional) `zh_cn` is available, but more may become available as people offer translation assistance.  If you wish to contribute translations; please visit https://translate.sakuraryoko.com -- and if you need a language file added; please contact me.

## Default Config File:
* File is loaded / saved upon Server start, or Vanilla data pack `/reload` command.
```json
{
  "DataProviderToggles": {
    "hud_data": true,
    "litematic_data": true,
    "structure_bounding_boxes": true,
    "servux_main": true,
    "tweaks_data": true,
    "entity_data": true
  },
  "hud_data": {
    "permission_level": 0,
    "update_interval": 40,
    "share_weather_status": false,
    "weather_permission_level": 0,
    "share_seed": false,
    "seed_permission_level": 2,
    "loggers_enabled": false,
    "loggers_enable_list": [
      "tps",
      "mob_caps"
    ],
    "logger_permission_level": 0
  },
  "litematic_data": {
    "permission_level": 0,
    "permission_level_paste": 0,
    "permission_level_verify": 0,
    "fix_rail_rotations": true,
    "fix_stairs_mirror": true,
    "fix_chest_mirror": true,
    "verify_max_result_positions": 200000,
    "verify_session_timeout": 300,
    "verify_syncmatica_interop": true,
    "verify_force_load_chunks": true,
    "verify_generate_missing_chunks": false,
    "verify_max_chunk_loads_per_tick": 2,
    "verify_pause_mspt_threshold": 45,
    "verify_nbt": true,
    "verify_nbt_slot_exact": false,
    "verify_nbt_strict": false
  },
  "structure_bounding_boxes": {
    "permission_level": 0,
    "structures_blacklist_enabled": false,
    "structures_whitelist_enabled": false,
    "structures_blacklist": [
      "minecraft:buried_treasure"
    ],
    "structures_whitelist": [],
    "update_interval": 40,
    "timeout": 600
  },
  "servux_main": {
    "permission_level": 0,
    "permission_level_admin": 3,
    "permission_level_easy_place": 0,
    "easy_place_validator_enabled": true,
    "default_language": "en_us",
    "debug_log": false
  },
  "tweaks_data": {
    "permission_level": 0,
    "update_interval": 120,
    "stackable_shulkers": false,
    "stackable_shulkers_count": 64,
    "stackable_shulkers_fix": true
  },
  "entity_data": {
    "permission_level": 0,
    "nbt_query_override": false,
    "nbt_query_permission_level": 2,
    "fix_allay_gathering": true,
    "nbt_allow_player_inventory": true,
    "nbt_allow_player_ender_items": true,
    "player_inventory_permission_level": 2,
    "player_ender_items_permission_level": 2
  }
}
```

## Future plans:
* Add Syncmatica-like protocol for Litematica.
  * Read-only Syncmatica interop already exists for `/servux verify`; sharing schematics over Servux's own channel is still to come.
* Extend server side verification:
  * Stream results to the Litematica Verifier GUI over the task response packets, so mismatches can be highlighted in world.  Advertised to clients through the `Features` metadata list rather than a protocol version bump.
  * Compare entities as well as blocks and container contents.
