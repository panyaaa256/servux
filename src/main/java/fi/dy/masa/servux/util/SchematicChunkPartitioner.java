package fi.dy.masa.servux.util;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.util.position.IntBoundingBox;
import fi.dy.masa.servux.util.position.LayerRange;
import fi.dy.masa.servux.util.position.PositionUtils;

/**
 * Splits a placement into the per-chunk boxes a chunk walking task has to visit.
 * <p>
 * This is the one piece of setup a paste and a read-only walk genuinely share: both have to
 * agree, box for box, on which chunks a placement touches and which part of each chunk falls
 * inside it - otherwise a verification would not be checking the blocks a paste of the same
 * placement would write. It lives here as a static so that the two task hierarchies can stay
 * separate; before this existed, the verify task inherited from the paste base purely to
 * borrow this method.
 */
public class SchematicChunkPartitioner
{
	/**
	 * @param boxConsumer   receives every clamped box, keyed by the chunk it falls in
	 * @param chunkConsumer receives each chunk that contributed at least one box, once
	 */
	public static void partition(SchematicPlacement placement,
	                             LayerRange range,
	                             Level world,
	                             BiConsumer<ChunkPos, IntBoundingBox> boxConsumer,
	                             Consumer<ChunkPos> chunkConsumer)
	{
		Set<ChunkPos> touchedChunks = placement.getTouchedChunks();

		for (ChunkPos pos : touchedChunks)
		{
			int count = 0;

			for (IntBoundingBox box : placement.getBoxesWithinChunk(pos.x(), pos.z()).values())
			{
				box = PositionUtils.getClampedBox(box, range);

				if (box != null)
				{
					// Clamp the box to the world bounds.
					// This is also important for the fill-based strip generation code to not
					// overflow the work array bounds.
					box = PositionUtils.clampBoxToWorldHeightRange(box, world);

					if (box != null)
					{
						boxConsumer.accept(pos, box);
						++count;
					}
				}
			}

			if (count > 0)
			{
				chunkConsumer.accept(pos);
			}
		}
	}
}
