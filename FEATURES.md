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
    * Chunks outside the client's render distance are loaded on demand, so a verification covers the whole build.  Loading is non-blocking and the chunks are loaded but §onot ticked§r -- no mob spawning, no redstone, no block or random ticks.  Chunks that have never been generated are §oreported, not generated§r, so inspecting a build never enlarges the world; `chunk_walk_generate_missing_chunks` opts into generating them.  New loads back off while the server's tick time is high (`chunk_walk_pause_mspt_threshold`), and a walk that can never catch up ends with the remainder reported rather than sitting in the scheduler forever.
    * `Wrong Contents` compares container inventories, which Litematica's client side verifier cannot do at all -- it only compares block states, so an empty chest counts as correct there.  Only checked where the block state already matches, so this count overlaps the correct-state count rather than adding to the other categories.
    * Can verify schematics already shared through Syncmatica without an upload, by reading its placement manifest from disk.  This is a read-only, unofficial interface and can be turned off with `verify_syncmatica_interop`.
    * Verify operations have a separate permissions node.
    * Results are streamed to the client in acknowledged batches (`task_batch_positions`), so a verification covering millions of positions never builds one oversized packet.  A session that nobody acknowledges is discarded after `task_session_timeout`.
  * Provides server side Area Analysis via `/servux analyze`, which counts what is actually in a region of the world without the client reading a single chunk.
    * Reports block counts per block state, entity counts per type, and the contents of every container in the area (`analyze_containers`), walking nested shulker boxes and bundles.
    * Shares the chunk walking policy with verification, so the same `chunk_walk_*` settings apply and an analysis is equally read-only.
    * `analyze_max_volume` caps how many blocks one analysis may read, so a mis-typed selection cannot ask the server to walk the whole world.
    * Analyze operations have a separate permissions node.
  * Provides server side Material Lists via `/servux materials`, or from a client's own placement, which prices up a schematic placement against the real world rather than against what the client happens to have loaded.
    * Reports, per block state, how many the placement needs, how many are still missing, and how many have some other block in the way; plus the entities the placement would spawn and the contents of every container in it.
    * This is the reason to do it server side: Litematica counts a block as missing when its own client world says air there, and outside the render distance that is every block.  A build larger than the render distance therefore reports as almost entirely missing on the client, which is exactly the material list a player is most likely to ask for.
    * `ignore_state` skips counting a block of the right type but the wrong state as missing, matching Litematica's own `Material List Ignore State` option.
    * Shares the chunk walking policy with verification, so the same `chunk_walk_*` settings apply and a material list is equally read-only, and it walks exactly the blocks a paste of the same placement would write.
    * Material list operations have a separate permissions node.
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
  * `analyze start` [from] [to] [entities] [containers] -- Starts a server side analysis of the region between the two corners.  [entities] and [containers] are optional and default to counting both.
  * `analyze status` -- Shows the progress of the analyses currently running.
  * `analyze cancel` [session] -- Cancels a running analysis; defaults to your own.
  * `analyze show` [page] -- Lists the tally from your latest analysis, most numerous first.
  * `materials start` [placement] [ignore_state] -- Starts a server side material list for a placement shared through Syncmatica.  [ignore_state] is optional and defaults to false.
  * `materials status` -- Shows the progress of the material lists currently running.
  * `materials cancel` [session] -- Cancels a running material list; defaults to your own.
  * `materials show` [page] -- Lists what your latest material list still needs, most missing first.
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
    "permission_level_tasks": 0,
    "permission_level_verify": 0,
    "permission_level_analyze": 0,
    "permission_level_materials": 0,
    "player_task_feedback": false,
    "fix_rail_rotations": true,
    "fix_stairs_mirror": true,
    "fix_chest_mirror": true,
    "deduplicate_schematic_entities": false,
    "verify_max_result_positions": 200000,
    "task_batch_positions": 16384,
    "task_session_timeout": 300,
    "verify_syncmatica_interop": true,
    "chunk_walk_force_load_chunks": true,
    "chunk_walk_generate_missing_chunks": false,
    "chunk_walk_max_loads_per_tick": 2,
    "chunk_walk_pause_mspt_threshold": 45,
    "verify_nbt": true,
    "verify_nbt_slot_exact": false,
    "verify_nbt_strict": false,
    "analyze_max_volume": 67108864,
    "analyze_containers": true
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
* Extend the server side task sessions:
  * Compare entities during verification, as well as blocks and container contents.
  * Server side schematic saving.  The task exists but nothing starts it yet, on either side.
  * Let a client cancel a fill or delete.  Those run on the upstream task manager, which tracks no owner, so only the session driven tasks can currently be stopped on request.
