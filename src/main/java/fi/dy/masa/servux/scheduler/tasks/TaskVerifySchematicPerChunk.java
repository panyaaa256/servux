package fi.dy.masa.servux.scheduler.tasks;

import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;

import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.verifier.VerifyEntityMatcher;
import fi.dy.masa.servux.schematic.verifier.VerifyNbtComparator;
import fi.dy.masa.servux.schematic.verifier.VerifyResult;
import fi.dy.masa.servux.util.PasteLayerBehavior;
import fi.dy.masa.servux.util.SchematicChunkPartitioner;
import fi.dy.masa.servux.util.SchematicVerifyUtils;
import fi.dy.masa.servux.util.chunk.ServerChunkLoader;
import fi.dy.masa.servux.util.position.LayerRange;

/**
 * Walks a placement chunk by chunk and classifies every block against the world.
 * <p>
 * The placement is partitioned into per-chunk boxes with the same
 * {@link SchematicChunkPartitioner} a paste uses, so that a verification walks exactly the
 * blocks a paste of the same placement would write. Everything about pacing, chunk loading
 * and giving up lives in {@link TaskChunkWalkerBase}; all that is left here is the
 * comparison itself.
 * <p>
 * When entities are verified too, a chunk additionally has to wait for its entities. Those
 * load separately from, and after, the chunk's blocks, so a chunk that has only just
 * become readable would otherwise report every entity in it as missing.
 */
public class TaskVerifySchematicPerChunk extends TaskChunkWalkerBase
{
	private final ImmutableList<SchematicPlacement> placements;
	private final LayerRange layerRange;
	private final PasteLayerBehavior layerBehavior;

	private final ArrayListMultimap<ChunkPos, SchematicPlacement> placementsPerChunk = ArrayListMultimap.create();
	private final VerifyResult result;
	@Nullable private final VerifyNbtComparator nbtComparator;
	@Nullable private final VerifyEntityMatcher entityMatcher;
	private boolean waitingForEntities;

	public TaskVerifySchematicPerChunk(TaskContext context,
	                                   Collection<SchematicPlacement> placements,
	                                   @Nullable LayerRange range,
	                                   PasteLayerBehavior layerBehavior,
	                                   VerifyResult result,
	                                   @Nullable ServerChunkLoader chunkLoader,
	                                   @Nullable VerifyNbtComparator nbtComparator,
	                                   @Nullable VerifyEntityMatcher entityMatcher,
	                                   int pauseMsptThreshold,
	                                   @Nullable Runnable onComplete)
	{
		super(context, result.getProgress(), chunkLoader, pauseMsptThreshold, onComplete);

		this.placements = ImmutableList.copyOf(placements);
		this.layerRange = range != null ? range : new LayerRange();
		this.layerBehavior = layerBehavior;
		this.result = result;
		this.nbtComparator = nbtComparator;
		this.entityMatcher = entityMatcher;
	}

	public VerifyResult getResult()
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
	protected boolean canProcessChunk(ChunkPos pos)
	{
		if (super.canProcessChunk(pos) == false)
		{
			return false;
		}

		// Checked after the loader has had its say, so that a chunk it is still bringing in
		// keeps being driven; once the blocks are there, this only waits for the entities
		if (this.entityMatcher == null || this.context.level().areEntitiesLoaded(pos.pack()))
		{
			return true;
		}

		this.waitingForEntities = true;

		return false;
	}

	@Override
	protected boolean isWaitingOnChunkData()
	{
		boolean waiting = this.waitingForEntities;
		this.waitingForEntities = false;

		return waiting;
	}

	@Override
	protected boolean processChunk(ChunkPos pos)
	{
		// New list to avoid CME
		ArrayList<SchematicPlacement> placements = new ArrayList<>(this.placementsPerChunk.get(pos));

		for (SchematicPlacement placement : placements)
		{
			SchematicVerifyUtils.verifyWorldWithinChunk(this.context.level(), pos, placement,
			                                            this.layerBehavior, this.layerRange,
			                                            this.result, this.nbtComparator, this.entityMatcher);

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
