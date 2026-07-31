package fi.dy.masa.servux.scheduler.tasks;

import java.util.ArrayList;
import java.util.Collection;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.verifier.VerifyResult;
import fi.dy.masa.servux.util.PasteLayerBehavior;
import fi.dy.masa.servux.util.ReplaceBehavior;
import fi.dy.masa.servux.util.SchematicVerifyUtils;
import fi.dy.masa.servux.util.position.LayerRange;

/**
 * Walks a placement chunk by chunk and classifies every block against the world.
 * <p>
 * Extends the paste base purely to inherit its {@code init()}/{@code addPlacement()} chunk
 * partitioning; the inherited {@code replaceBehavior} is unused for a read-only pass.
 * <p>
 * Chunk loading policy: this task only reads chunks that are <i>already</i> loaded, and
 * only the chunk being processed (unlike a paste, which needs the 3x3 neighbourhood for
 * block updates). Chunks that never become available are reported as unloaded rather than
 * being force-loaded, so a verification can never stall the server or touch world gen.
 */
public class TaskVerifySchematicPerChunk extends TaskPasteSchematicPerChunkBase
{
	/**
	 * How many consecutive ticks without progress to tolerate before declaring the
	 * remaining chunks unreachable. Chunk loads driven by other players are asynchronous,
	 * so a short grace period avoids giving up on a chunk that is about to arrive.
	 */
	private static final int STUCK_TICK_LIMIT = 100;

	private final ArrayListMultimap<ChunkPos, SchematicPlacement> placementsPerChunk = ArrayListMultimap.create();
	private final VerifyResult result;
	@Nullable private Runnable onComplete;
	private int stuckTicks;
	private boolean cancelled;

	public TaskVerifySchematicPerChunk(TaskContext context,
	                                   Collection<SchematicPlacement> placements,
	                                   @Nullable LayerRange range,
	                                   PasteLayerBehavior layerBehavior,
	                                   VerifyResult result,
	                                   @Nullable Runnable onComplete)
	{
		super(context, placements, range != null ? range : new LayerRange(), ReplaceBehavior.NONE, layerBehavior);

		this.result = result;
		this.onComplete = onComplete;
		this.name = "verify";
	}

	public VerifyResult getResult()
	{
		return this.result;
	}

	/** Runs on the server thread once the task stops, whether it finished or was cancelled. */
	public void setOnComplete(@Nullable Runnable onComplete)
	{
		this.onComplete = onComplete;
	}

	/** Requests that the task stop at the next tick; the partial result is kept. */
	public void cancel()
	{
		this.cancelled = true;
	}

	public boolean isCancelled()
	{
		return this.cancelled;
	}

	@Override
	public void init()
	{
		super.init();

		this.result.setTotalChunks(this.pendingChunks.size());
	}

	@Override
	public boolean canExecute()
	{
		return super.canExecute() && this.context.world() != null && !this.cancelled;
	}

	@Override
	protected void onChunkAddedForHandling(ChunkPos pos, SchematicPlacement placement)
	{
		super.onChunkAddedForHandling(pos, placement);

		this.placementsPerChunk.put(pos, placement);
	}

	/**
	 * Only the chunk itself has to be present; verification reads block states and never
	 * triggers neighbour updates, so the paste base's 3x3 requirement does not apply.
	 */
	@Override
	protected boolean canProcessChunk(ChunkPos pos)
	{
		return this.isServerChunkLoaded(this.context.world(), pos.x(), pos.z());
	}

	@Override
	public boolean execute(ProfilerFiller profiler)
	{
		profiler.push("per_chunk_verify");

		MinecraftServer server = this.context.server();

		if (server == null)
		{
			profiler.pop();
			return true;
		}

		final long vanillaTickTime = server.getTickTimesNanos()[server.getTickCount() % 100];
		final long timeStart = Util.getNanos();
		boolean budgetExhausted = false;
		int processedThisTick = 0;

		this.sortChunkList();

		for (int chunkIndex = 0; chunkIndex < this.pendingChunks.size(); ++chunkIndex)
		{
			long currentTime = Util.getNanos();
			long elapsedTickTime = vanillaTickTime + (currentTime - timeStart);

			if (elapsedTickTime >= 60000000L)
			{
				budgetExhausted = true;
				break;
			}

			profiler.push("process_chunk");

			ChunkPos pos = this.pendingChunks.get(chunkIndex);

			if (this.canProcessChunk(pos) && this.processChunk(pos))
			{
				this.pendingChunks.remove(chunkIndex);
				--chunkIndex;
				++processedThisTick;
			}

			profiler.pop();
		}

		if (this.pendingChunks.isEmpty())
		{
			this.finished = true;
			profiler.pop();
			return true;
		}

		// Nothing left to wait for: the remaining chunks are not loaded and nobody is
		// loading them. Report them instead of spinning forever.
		if (processedThisTick > 0 || budgetExhausted)
		{
			this.stuckTicks = 0;
		}
		else if (++this.stuckTicks > STUCK_TICK_LIMIT)
		{
			this.result.setUnloadedChunks(this.pendingChunks.size());
			this.pendingChunks.clear();
			this.finished = true;
			profiler.pop();
			return true;
		}

		profiler.pop();
		return false;
	}

	@Override
	protected boolean processChunk(ChunkPos pos)
	{
		// New list to avoid CME
		ArrayList<SchematicPlacement> placements = new ArrayList<>(this.placementsPerChunk.get(pos));

		for (SchematicPlacement placement : placements)
		{
			SchematicVerifyUtils.verifyWorldWithinChunk(this.context.world(), pos, placement,
			                                            this.layerBehavior, this.layerRange, this.result);

			this.placementsPerChunk.remove(pos, placement);
		}

		this.result.addProcessedChunk();

		return this.placementsPerChunk.containsKey(pos) == false;
	}

	@Override
	protected void onStop()
	{
		if (!this.finished && !this.cancelled)
		{
			// Aborted for a reason other than an explicit cancel (world unloaded, etc.)
			this.result.setUnloadedChunks(this.pendingChunks.size());
		}

		// onStop() is already dispatched onto the server thread by the base class
		if (this.onComplete != null)
		{
			this.onComplete.run();
		}

		super.onStop();
	}
}
