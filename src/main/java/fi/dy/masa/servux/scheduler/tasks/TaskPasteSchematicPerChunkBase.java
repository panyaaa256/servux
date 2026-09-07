package fi.dy.masa.servux.scheduler.tasks;

import java.util.Collection;
import com.google.common.collect.ImmutableList;

import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.util.PasteLayerBehavior;
import fi.dy.masa.servux.util.ReplaceBehavior;
import fi.dy.masa.servux.util.SchematicChunkPartitioner;
import fi.dy.masa.servux.util.position.LayerRange;

public abstract class TaskPasteSchematicPerChunkBase extends TaskProcessChunkMultiPhase
{
	protected final ImmutableList<SchematicPlacement> placements;
	protected final LayerRange layerRange;
	protected final ReplaceBehavior replaceBehavior;
	protected final PasteLayerBehavior layerBehavior;
	protected final boolean changedBlockOnly;
	protected final boolean ignoreBlocks;
	protected final boolean ignoreEntities;

	public TaskPasteSchematicPerChunkBase(TaskContext context,
	                                      final Collection<SchematicPlacement> placements,
	                                      final LayerRange layerRange,
	                                      final ReplaceBehavior replaceBehavior,
	                                      final PasteLayerBehavior layerBehavior,
	                                      final boolean changedBlockOnly,
	                                      final boolean ignoreBlocks,
	                                      final boolean ignoreEntities)
	{
		super(context);

		this.placements = ImmutableList.copyOf(placements);
		this.layerRange = layerRange;
		this.replaceBehavior = replaceBehavior;
		this.layerBehavior = layerBehavior;
		this.changedBlockOnly = changedBlockOnly;
		this.ignoreBlocks = ignoreBlocks;
		this.ignoreEntities = ignoreEntities;
	}

	@Override
	public void init()
	{
		for (SchematicPlacement placement : this.placements)
		{
			this.addPlacement(placement, this.layerRange);
		}

		this.pendingChunks.clear();
		this.pendingChunks.addAll(this.boxesInChunks.keySet());
		this.sortChunkList();
	}

	protected void addPlacement(SchematicPlacement placement, LayerRange range)
	{
		SchematicChunkPartitioner.partition(placement, range, this.context.level(),
		                                    this.boxesInChunks::put,
		                                    pos -> this.onChunkAddedForHandling(pos, placement));
	}

	protected void onChunkAddedForHandling(ChunkPos pos, SchematicPlacement placement)
	{
	}

	@Override
	protected boolean canProcessChunk(ChunkPos pos)
	{
		return this.areSurroundingChunksLoaded(pos, this.context.level(), 1);
	}
}
