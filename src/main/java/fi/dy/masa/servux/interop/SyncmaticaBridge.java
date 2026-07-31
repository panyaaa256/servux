package fi.dy.masa.servux.interop;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import fi.dy.masa.servux.Reference;
import fi.dy.masa.servux.Servux;
import fi.dy.masa.servux.schematic.LitematicaSchematic;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.util.data.json.JsonUtils;

/**
 * Read-only bridge to schematics shared through Syncmatica.
 * <p>
 * Deliberately has no compile time dependency on Syncmatica: several forks exist
 * (End-Tech, sakura-ryoko, bunnyi116) with differing maven coordinates and package names.
 * Instead this reads the two things Syncmatica leaves on disk:
 * <ul>
 *   <li>{@code <worldDir>/syncmatica/placements.json} - the placement manifest. Syncmatica
 *       writes it via a {@code .new}/{@code .bak} dance, so a torn read is unlikely but
 *       possible; a parse failure simply disables interop for that read.</li>
 *   <li>{@code <gameDir>/syncmatics/<hash>.litematic} - the schematics themselves. These
 *       are content addressed (the file's MD5 as a UUID), hence immutable once written,
 *       so there is no read/write race on them.</li>
 * </ul>
 * The manifest is an unofficial interface and may change shape between Syncmatica
 * versions. Every parse is therefore defensive: a placement that cannot be understood is
 * skipped with a warning rather than failing the whole listing.
 */
public class SyncmaticaBridge
{
	public static final SyncmaticaBridge INSTANCE = new SyncmaticaBridge();

	private static final String MANIFEST_NAME = "placements.json";

	private List<SyncmaticaPlacement> cached = Collections.emptyList();
	private long cachedMtime = -1L;
	private Path cachedManifest;

	private SyncmaticaBridge() {}

	/**
	 * One entry of Syncmatica's placement manifest.
	 *
	 * @param id          Syncmatica's own placement UUID
	 * @param displayName the name shown to users, falling back to the file name
	 * @param hash        content hash, which is also the schematic's file name
	 * @param origin      the placement origin in world coordinates
	 * @param dimension   the dimension the placement lives in
	 */
	public record SyncmaticaPlacement(UUID id, String displayName, String fileName, String hash,
	                                  BlockPos origin, ResourceKey<Level> dimension,
	                                  Rotation rotation, Mirror mirror)
	{
	}

	/**
	 * The directory holding Syncmatica's manifest, i.e. {@code <worldDir>/syncmatica}.
	 */
	@Nullable
	public Path getManifestDir(MinecraftServer server)
	{
		try
		{
			Path worldDir = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize();
			Path dir = worldDir.resolve("syncmatica");

			return Files.isDirectory(dir) ? dir : null;
		}
		catch (Exception e)
		{
			Servux.LOGGER.warn("SyncmaticaBridge: failed to resolve the world directory; {}", e.getLocalizedMessage());
			return null;
		}
	}

	/**
	 * The directory holding the shared schematic files, i.e. {@code <gameDir>/syncmatics}.
	 */
	public Path getSchematicDir()
	{
		return Reference.DEFAULT_RUN_DIR.resolve("syncmatics").normalize();
	}

	/**
	 * True when Syncmatica data is present on disk. Deliberately a path check rather than
	 * {@code FabricLoader.isModLoaded("syncmatica")}: the data is what matters, and the
	 * mod id differs across forks.
	 */
	public boolean isAvailable(MinecraftServer server)
	{
		Path dir = this.getManifestDir(server);

		return dir != null && Files.isReadable(dir.resolve(MANIFEST_NAME));
	}

	/**
	 * Reads the placement manifest, reusing the previous parse while the file's mtime is
	 * unchanged.
	 *
	 * @return the known placements, or an empty list if Syncmatica data is absent or unreadable
	 */
	public List<SyncmaticaPlacement> getPlacements(MinecraftServer server)
	{
		Path dir = this.getManifestDir(server);

		if (dir == null)
		{
			return Collections.emptyList();
		}

		Path manifest = dir.resolve(MANIFEST_NAME);

		if (!Files.isReadable(manifest))
		{
			return Collections.emptyList();
		}

		try
		{
			long mtime = Files.getLastModifiedTime(manifest).toMillis();

			if (mtime == this.cachedMtime && manifest.equals(this.cachedManifest))
			{
				return this.cached;
			}

			List<SyncmaticaPlacement> parsed = this.parseManifest(manifest);

			this.cached = parsed;
			this.cachedMtime = mtime;
			this.cachedManifest = manifest;

			return parsed;
		}
		catch (Exception e)
		{
			// A torn read while Syncmatica is rewriting the file: keep whatever we had.
			Servux.LOGGER.warn("SyncmaticaBridge: failed to read '{}'; {}", manifest.toAbsolutePath(), e.getLocalizedMessage());

			return this.cached;
		}
	}

	private List<SyncmaticaPlacement> parseManifest(Path manifest) throws Exception
	{
		List<SyncmaticaPlacement> list = new ArrayList<>();

		try (BufferedReader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8))
		{
			JsonElement root = JsonParser.parseReader(reader);

			if (!root.isJsonObject())
			{
				return list;
			}

			JsonObject obj = root.getAsJsonObject();

			if (!JsonUtils.hasArray(obj, "placements"))
			{
				return list;
			}

			JsonArray placements = obj.getAsJsonArray("placements");

			for (JsonElement element : placements)
			{
				if (!element.isJsonObject())
				{
					continue;
				}

				SyncmaticaPlacement placement = this.parsePlacement(element.getAsJsonObject());

				if (placement != null)
				{
					list.add(placement);
				}
			}
		}

		return list;
	}

	@Nullable
	private SyncmaticaPlacement parsePlacement(JsonObject obj)
	{
		try
		{
			String idStr = JsonUtils.getStringOrDefault(obj, "id", null);
			String hash = JsonUtils.getStringOrDefault(obj, "hash", null);
			String fileName = JsonUtils.getStringOrDefault(obj, "file_name", null);

			if (idStr == null || hash == null)
			{
				return null;
			}

			String displayName = JsonUtils.getStringOrDefault(obj, "display_name", null);

			if (displayName == null || displayName.isEmpty())
			{
				displayName = fileName != null ? fileName : hash;
			}

			BlockPos origin = BlockPos.ZERO;
			ResourceKey<Level> dimension = Level.OVERWORLD;

			if (JsonUtils.hasObject(obj, "origin"))
			{
				JsonObject originObj = obj.getAsJsonObject("origin");
				BlockPos parsedPos = parsePos(originObj.get("position"));

				if (parsedPos != null)
				{
					origin = parsedPos;
				}

				String dimStr = JsonUtils.getStringOrDefault(originObj, "dimension", null);
				ResourceKey<Level> parsedDim = parseDimension(dimStr);

				if (parsedDim != null)
				{
					dimension = parsedDim;
				}
			}

			// Syncmatica stores these enums by name in JSON, unlike the ordinals it uses on the wire
			Rotation rotation = parseEnum(Rotation.class, JsonUtils.getStringOrDefault(obj, "rotation", null), Rotation.NONE);
			Mirror mirror = parseEnum(Mirror.class, JsonUtils.getStringOrDefault(obj, "mirror", null), Mirror.NONE);

			return new SyncmaticaPlacement(UUID.fromString(idStr), displayName,
			                               fileName != null ? fileName : hash, hash,
			                               origin, dimension, rotation, mirror);
		}
		catch (Exception e)
		{
			Servux.LOGGER.warn("SyncmaticaBridge: skipping an unparseable placement entry; {}", e.getLocalizedMessage());
			return null;
		}
	}

	/** Accepts both {@code {"x":..,"y":..,"z":..}} and {@code [x,y,z]} shapes. */
	@Nullable
	private static BlockPos parsePos(@Nullable JsonElement element)
	{
		if (element == null)
		{
			return null;
		}

		try
		{
			if (element.isJsonObject())
			{
				JsonObject obj = element.getAsJsonObject();

				if (JsonUtils.hasInteger(obj, "x") && JsonUtils.hasInteger(obj, "y") && JsonUtils.hasInteger(obj, "z"))
				{
					return new BlockPos(JsonUtils.getInteger(obj, "x"),
					                    JsonUtils.getInteger(obj, "y"),
					                    JsonUtils.getInteger(obj, "z"));
				}
			}
			else if (element.isJsonArray())
			{
				JsonArray array = element.getAsJsonArray();

				if (array.size() >= 3)
				{
					return new BlockPos(array.get(0).getAsInt(), array.get(1).getAsInt(), array.get(2).getAsInt());
				}
			}
		}
		catch (Exception ignored) {}

		return null;
	}

	@Nullable
	private static ResourceKey<Level> parseDimension(@Nullable String value)
	{
		if (value == null || value.isEmpty())
		{
			return null;
		}

		Identifier id = Identifier.tryParse(value);

		return id != null ? ResourceKey.create(Registries.DIMENSION, id) : null;
	}

	private static <E extends Enum<E>> E parseEnum(Class<E> clazz, @Nullable String value, E fallback)
	{
		if (value == null || value.isEmpty())
		{
			return fallback;
		}

		for (E constant : clazz.getEnumConstants())
		{
			if (constant.name().equalsIgnoreCase(value))
			{
				return constant;
			}
		}

		return fallback;
	}

	/**
	 * Looks a placement up by its UUID or (case-insensitively) by its display name.
	 */
	@Nullable
	public SyncmaticaPlacement findPlacement(MinecraftServer server, String nameOrId)
	{
		List<SyncmaticaPlacement> placements = this.getPlacements(server);

		try
		{
			UUID id = UUID.fromString(nameOrId);

			for (SyncmaticaPlacement placement : placements)
			{
				if (placement.id().equals(id))
				{
					return placement;
				}
			}
		}
		catch (IllegalArgumentException ignored) {}

		for (SyncmaticaPlacement placement : placements)
		{
			if (placement.displayName().equalsIgnoreCase(nameOrId))
			{
				return placement;
			}
		}

		return null;
	}

	/**
	 * Loads the schematic behind a Syncmatica placement and builds a Servux
	 * {@link SchematicPlacement} from it.
	 * <p>
	 * Sub-region placements are left at the schematic's own values: the manifest's
	 * {@code subregionData} shape is not stable enough across forks to rely on, and an
	 * unmodified placement (the overwhelmingly common case) is exactly the default.
	 *
	 * @return null if the schematic file is missing or unreadable
	 */
	@Nullable
	public SchematicPlacement loadPlacement(SyncmaticaPlacement info)
	{
		Path dir = this.getSchematicDir();
		String fileName = info.hash() + LitematicaSchematic.FILE_EXTENSION;
		Path file = dir.resolve(fileName);

		if (!Files.isReadable(file))
		{
			Servux.LOGGER.warn("SyncmaticaBridge: schematic file '{}' for placement '{}' is missing", file.toAbsolutePath(), info.displayName());
			return null;
		}

		LitematicaSchematic schematic = LitematicaSchematic.createFromFile(dir, fileName);

		if (schematic == null)
		{
			Servux.LOGGER.warn("SyncmaticaBridge: failed to load schematic '{}' for placement '{}'", file.toAbsolutePath(), info.displayName());
			return null;
		}

		SchematicPlacement placement = SchematicPlacement.createFor(schematic, info.origin(), info.displayName(), true, info.id());

		placement.setRotation(info.rotation());
		placement.setMirror(info.mirror());

		return placement;
	}

	@Nullable
	public ServerLevel getLevel(MinecraftServer server, SyncmaticaPlacement info)
	{
		return server.getLevel(info.dimension());
	}

	/** Drops the manifest cache, forcing a re-read on the next access. */
	public void invalidate()
	{
		this.cachedMtime = -1L;
		this.cached = Collections.emptyList();
	}
}
