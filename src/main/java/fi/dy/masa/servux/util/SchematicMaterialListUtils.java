package fi.dy.masa.servux.util;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.schematic.materials.MaterialListResult;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.ListData;
import fi.dy.masa.servux.util.position.LayerRange;

/**
 * Builds a material list for a placement, the way Litematica's
 * {@code TaskCountBlocksPlacement} does, but against the server's world.
 * <p>
 * The classification per position is deliberately identical to the client's: a non-air
 * schematic block counts towards the total; it counts as missing if the world has air
 * there, or has some other block that {@code ignoreState} does not excuse; and in the
 * latter case it is additionally counted as mismatched. What differs is only the world
 * being asked - the client's is empty outside its render distance, so its "missing" column
 * is really "missing, or too far away to know", and on a server that is most of a big build.
 * <p>
 * Entities and container contents come out of the schematic rather than the world, exactly
 * as on the client: they are what the placement would <i>add</i>, so there is nothing in
 * the world to compare them against and they are always reported as needed. The client
 * could tally those itself from the schematic it uploaded; they are counted here anyway so
 * that one walk produces a complete result, and so that the command path - which has no
 * client at all - reports the same numbers as the packet path.
 * <p>
 * One deliberate omission against the client: Litematica's {@code IGNORE_CROP_AGE} visual
 * setting also excuses a crop of the wrong age. That is a client rendering preference with
 * no server side counterpart, and server side verification does not honour it either.
 */
public class SchematicMaterialListUtils
{
	/**
	 * Counts every sub-region of the given placement that touches {@code chunkPos}.
	 *
	 * @param ignoreState     do not count a block of the right type but the wrong state as
	 *                        missing, matching Litematica's {@code MATERIAL_LIST_IGNORE_STATE}
	 * @param countEntities   tally the entities the placement would spawn
	 * @param countContainers tally the contents of the containers the placement would place
	 * @return false if any sub-region had missing/invalid schematic data
	 */
	public static boolean countWithinChunk(ServerLevel world,
	                                       ChunkPos chunkPos,
	                                       SchematicPlacement schematicPlacement,
	                                       PasteLayerBehavior layerBehavior,
	                                       @Nullable LayerRange layerRange,
	                                       MaterialListResult result,
	                                       boolean ignoreState,
	                                       boolean countEntities,
	                                       boolean countContainers)
	{
		// Null skips the entity walk outright, rather than walking it to discard everything
		SchematicRegionWalker.EntityVisitor entityVisitor =
				countEntities || countContainers
				? (pos, nbt) -> countEntity(nbt, result, countEntities, countContainers)
				: null;

		return SchematicRegionWalker.walkChunk(chunkPos, schematicPlacement, layerBehavior, layerRange,
		                                       (pos, expected, teNBT) ->
				                                       countBlock(world, pos, expected, teNBT, result,
				                                                  ignoreState, countContainers),
		                                       entityVisitor);
	}

	/**
	 * Classifies one schematic block against the world.
	 * <p>
	 * The reference comparison on the states is intentional and mirrors the client side
	 * code: block states are canonical instances, so {@code !=} is an equality test here.
	 */
	private static void countBlock(ServerLevel world, BlockPos pos, BlockState expected,
	                               @Nullable CompoundData blockEntityNbt, MaterialListResult result,
	                               boolean ignoreState, boolean countContainers)
	{
		// Air costs nothing to place, so it is not part of a material list at all
		if (expected.isAir())
		{
			return;
		}

		result.addTotal(expected);

		BlockState found = world.getBlockState(pos);

		if (found.isAir())
		{
			result.addMissing(expected);
		}
		else if (found != expected && (!ignoreState || found.getBlock() != expected.getBlock()))
		{
			// Still missing: the right block has to be brought regardless of what is in the way
			result.addMissing(expected);
			result.addMismatch(expected);
		}

		if (countContainers && blockEntityNbt != null)
		{
			addStoredItems(blockEntityNbt, result);
		}
	}

	private static void countEntity(CompoundData nbt, MaterialListResult result,
	                                boolean countEntities, boolean countContainers)
	{
		if (countEntities)
		{
			Identifier id = Identifier.tryParse(nbt.getStringOrDefault("id", ""));

			if (id != null)
			{
				result.addEntity(id);
			}
		}

		// Minecarts with chests, hoppers and so on hold items just like a block does
		if (countContainers)
		{
			addStoredItems(nbt, result);
		}
	}

	/** Counts the {@code Items} list of a container block entity or entity tag, if it has one. */
	private static void addStoredItems(CompoundData tag, MaterialListResult result)
	{
		ListData items = tag.getList("Items");

		for (int i = 0; i < items.size(); i++)
		{
			addItem(items.getCompoundAt(i), result);
		}
	}

	/**
	 * Counts one stored stack, and - since shulker boxes cannot be nested - unpacks one
	 * extra level of {@code minecraft:container} contents.
	 * <p>
	 * The box itself is counted <i>as well as</i> what is in it, unlike an area analysis
	 * which reports the contents instead: a material list is a shopping list, and a
	 * placement containing a full shulker box needs both the box and its contents brought.
	 * Bundles are intentionally not unpacked, matching Litematica.
	 */
	private static void addItem(CompoundData itemTag, MaterialListResult result)
	{
		addItemCount(itemTag, result);

		ListData contents = itemTag.getCompound("components").getList("minecraft:container");

		for (int i = 0; i < contents.size(); i++)
		{
			addItemCount(contents.getCompoundAt(i).getCompound("item"), result);
		}
	}

	private static void addItemCount(CompoundData itemTag, MaterialListResult result)
	{
		Identifier id = Identifier.tryParse(itemTag.getStringOrDefault("id", ""));

		if (id == null)
		{
			return;
		}

		// The vanilla item codec omits "count" when it is 1, so an absent count is one item
		// and not none - reading it as 0 would silently drop every unstacked tool and armour
		// piece out of the list
		final int count = itemTag.getIntOrDefault("count", 1);

		if (count > 0)
		{
			result.addContainerItem(id, count);
		}
	}
}
