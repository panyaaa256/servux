package fi.dy.masa.servux.scheduler.tasks;

import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;

import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.schematic.materials.MaterialListResult;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.util.PasteLayerBehavior;
import fi.dy.masa.servux.util.SchematicChunkPartitioner;
import fi.dy.masa.servux.util.SchematicMaterialListUtils;
import fi.dy.masa.servux.util.chunk.ServerChunkLoader;
import fi.dy.masa.servux.util.LayerRange;

/**
 * Walks a placement chunk by chunk and tallies what it would cost to build.
 * <p>
 * Structurally the same walk as {@link TaskVerifySchematicPerChunk} - same partitioner,
 * same per-chunk bookkeeping - and for the same reason: a material list has to cover the
 * blocks a paste of the placement would write, no more and no fewer. The two differ only
 * in what they do with each position, which is why everything else lives in
 * {@link TaskChunkWalkerBase} and {@code SchematicRegionWalker}.
 * <p>
 * What makes it worth doing here rather than on the client is the world lookup: Litematica
 * counts a block as missing when its own client world says air, and outside the render
 * distance that is every block. The server sees the whole build.
 */
public class TaskMaterialListPlacement extends TaskChunkWalkerBase
{
	private final ImmutableList<SchematicPlacement> placements;
	private final LayerRange layerRange;
	private final PasteLayerBehavior layerBehavior;

	private final ArrayListMultimap<ChunkPos, SchematicPlacement> placementsPerChunk = ArrayListMultimap.create();
	private final MaterialListResult result;
	private final boolean ignoreState;
	private final boolean countEntities;
	private final boolean countContainers;

	public TaskMaterialListPlacement(TaskContext context,
	                                 Collection<SchematicPlacement> placements,
	                                 @Nullable LayerRange range,
	                                 PasteLayerBehavior layerBehavior,
	                                 MaterialListResult result,
	                                 @Nullable ServerChunkLoader chunkLoader,
	                                 boolean ignoreState,
	                                 boolean countEntities,
	                                 boolean countContainers,
	                                 int pauseMsptThreshold,
	                                 @Nullable Runnable onComplete)
	{
		super(context, result.getProgress(), chunkLoader, pauseMsptThreshold, onComplete);

		this.placements = ImmutableList.copyOf(placements);
		this.layerRange = range != null ? range : new LayerRange();
		this.layerBehavior = layerBehavior;
		this.result = result;
		this.ignoreState = ignoreState;
		this.countEntities = countEntities;
		this.countContainers = countContainers;
	}

	public MaterialListResult getResult()
	{
		return this.result;
	}

	@Override
	public void init()
	{
		for (SchematicPlacement placement : this.placements)
		{
			SchematicChunkPartitioner.partition(placement, this.layerRange, this.context.level(),
			                                    this.boxesInChunks::put,
			                                    pos -> this.placementsPerChunk.put(pos, placement));
		}

		this.pendingChunks.clear();
		this.pendingChunks.addAll(this.boxesInChunks.keySet());
		this.sortChunkList();

		super.init();
	}

	@Override
	protected boolean processChunk(ChunkPos pos)
	{
		// New list to avoid CME
		ArrayList<SchematicPlacement> placements = new ArrayList<>(this.placementsPerChunk.get(pos));

		for (SchematicPlacement placement : placements)
		{
			SchematicMaterialListUtils.countWithinChunk(this.context.level(), pos, placement,
			                                            this.layerBehavior, this.layerRange, this.result,
			                                            this.ignoreState, this.countEntities, this.countContainers);

			this.placementsPerChunk.remove(pos, placement);
		}

		this.progress.addProcessedChunk();

		// Read and done: let go of the chunk so it can unload again
		if (this.chunkLoader != null)
		{
			this.chunkLoader.release(pos);
		}

		return this.placementsPerChunk.containsKey(pos) == false;
	}
}
