package fi.dy.masa.servux.scheduler.tasks;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.verifier.VerifyChunkLoader;
import fi.dy.masa.servux.schematic.verifier.VerifyNbtComparator;
import fi.dy.masa.servux.schematic.verifier.VerifyResult;
import fi.dy.masa.servux.util.IntBoundingBox;
import fi.dy.masa.servux.util.LayerRange;
import fi.dy.masa.servux.util.PasteLayerBehavior;
import fi.dy.masa.servux.util.SchematicVerifyUtils;
import fi.dy.masa.servux.util.position.PositionUtils;

/**
 * Walks a placement chunk by chunk and classifies every block against the world.
 * <p>
 * On the 26.1+ branch this extends {@code TaskPasteSchematicPerChunkBase} to inherit its
 * chunk partitioning. That class does not exist here, and backporting it would drag the
 * whole paste rewrite onto an LTS branch that deliberately still uses the direct
 * {@code pasteTo()} path, so {@link #init()} and {@link #addPlacement} are carried here
 * instead - copied from upstream so the two stay comparable.
 * <p>
 * Chunk loading policy: this task only reads chunks that are <i>already</i> loaded, and
 * only the chunk being processed (unlike a paste, which needs the 3x3 neighbourhood for
 * block updates). Chunks that never become available are reported as unloaded rather than
 * being force-loaded, so a verification can never stall the server or touch world gen.
 */
public class TaskVerifySchematicPerChunk extends TaskProcessChunkBase
{
	/**
	 * How many consecutive ticks without progress to tolerate before declaring the
	 * remaining chunks unreachable. Chunk loads driven by other players are asynchronous,
	 * so a short grace period avoids giving up on a chunk that is about to arrive.
	 */
	private static final int STUCK_TICK_LIMIT = 100;

	/**
	 * Upper bound on time spent waiting on chunk loads or on the TPS backoff, so that a
	 * permanently overloaded server ends the verification instead of stranding it. Five
	 * minutes at 20 ticks per second.
	 */
	private static final int WAITING_TICK_LIMIT = 6000;

	/** How often to emit a progress ping, in ticks. */
	private static final int PROGRESS_TICK_INTERVAL = 20;

	protected final ImmutableList<SchematicPlacement> placements;
	protected final LayerRange layerRange;
	protected final PasteLayerBehavior layerBehavior;

	private final ArrayListMultimap<ChunkPos, SchematicPlacement> placementsPerChunk = ArrayListMultimap.create();
	private final VerifyResult result;
	@Nullable private final VerifyChunkLoader chunkLoader;
	@Nullable private final VerifyNbtComparator nbtComparator;
	private final int pauseMsptThreshold;
	private final List<ChunkPos> ungenerated = new ArrayList<>();
	@Nullable private Runnable onComplete;
	@Nullable private Runnable onProgress;
	private int ticksSinceProgress;
	private int stuckTicks;
	private int waitingTicks;
	private boolean cancelled;

	public TaskVerifySchematicPerChunk(TaskContext context,
	                                   Collection<SchematicPlacement> placements,
	                                   @Nullable LayerRange range,
	                                   PasteLayerBehavior layerBehavior,
	                                   VerifyResult result,
	                                   @Nullable VerifyChunkLoader chunkLoader,
	                                   @Nullable VerifyNbtComparator nbtComparator,
	                                   int pauseMsptThreshold,
	                                   @Nullable Runnable onComplete)
	{
		super(context);

		this.placements = ImmutableList.copyOf(placements);
		this.layerRange = range != null ? range : new LayerRange();
		this.layerBehavior = layerBehavior;
		this.result = result;
		this.chunkLoader = chunkLoader;
		this.nbtComparator = nbtComparator;
		this.pauseMsptThreshold = pauseMsptThreshold;
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

	/**
	 * Runs periodically while the task is working, for progress reporting. Verification of
	 * a large build takes a while, so the requester needs to see it moving.
	 */
	public void setOnProgress(@Nullable Runnable onProgress)
	{
		this.onProgress = onProgress;
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
		for (SchematicPlacement placement : this.placements)
		{
			this.addPlacement(placement, this.layerRange);
		}

		this.pendingChunks.clear();
		this.pendingChunks.addAll(this.boxesInChunks.keySet());
		this.sortChunkList();

		this.result.setTotalChunks(this.pendingChunks.size());
	}

	protected void addPlacement(SchematicPlacement placement, LayerRange range)
	{
		Set<ChunkPos> touchedChunks = placement.getTouchedChunks();

		for (ChunkPos pos : touchedChunks)
		{
			int count = 0;

			for (IntBoundingBox box : placement.getBoxesWithinChunk(pos.x, pos.z).values())
			{
				box = PositionUtils.getClampedBox(box, range);

				if (box != null)
				{
					// Clamp the box to the world bounds
					box = PositionUtils.clampBoxToWorldHeightRange(box, this.context.world());

					if (box != null)
					{
						this.boxesInChunks.put(pos, box);
						++count;
					}
				}
			}

			if (count > 0)
			{
				this.placementsPerChunk.put(pos, placement);
			}
		}
	}

	@Override
	public boolean canExecute()
	{
		return super.canExecute() && this.context.world() != null && !this.cancelled;
	}

	/**
	 * Only the chunk itself has to be present; verification reads block states and never
	 * triggers neighbour updates, so a paste's 3x3 requirement does not apply.
	 */
	@Override
	protected boolean canProcessChunk(ChunkPos pos)
	{
		if (this.isServerChunkLoaded(this.context.world(), pos.x, pos.z))
		{
			return true;
		}

		if (this.chunkLoader == null)
		{
			return false;
		}

		// With force loading on, this also drives the load: it asks the loader to bring
		// the chunk in and reports READY only once it is actually readable, so the caller
		// reads it during the very tick it became available.
		VerifyChunkLoader.Result result = this.chunkLoader.request(pos);

		if (result == VerifyChunkLoader.Result.UNGENERATED)
		{
			// Nothing to compare against, and generating it is not on the table
			this.ungenerated.add(pos);
		}

		return result == VerifyChunkLoader.Result.READY;
	}

	/**
	 * True while the server is running hot enough that we should stop pulling new chunks
	 * in. Already loaded chunks keep being verified; only the extra I/O is paused.
	 */
	private boolean shouldPauseForTps(MinecraftServer server)
	{
		if (this.pauseMsptThreshold <= 0)
		{
			return false;
		}

		return (server.getAverageTickTimeNanos() / 1_000_000.0D) > this.pauseMsptThreshold;
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

		// Back off from issuing new loads while the server is struggling; verification is
		// never urgent enough to compete with the players on it. Chunks that are already
		// in memory keep being verified, only the extra I/O pauses. Without a loader there
		// is no I/O to pause, so the threshold does not apply at all.
		final boolean paused = this.chunkLoader != null && this.shouldPauseForTps(server);

		if (this.chunkLoader != null)
		{
			this.chunkLoader.startTick(!paused);
		}

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

		if (this.onProgress != null && ++this.ticksSinceProgress >= PROGRESS_TICK_INTERVAL)
		{
			this.ticksSinceProgress = 0;
			this.onProgress.run();
		}

		// Chunks that turned out never to have been generated: there is nothing to compare
		// against, and generating them is exactly what this must not do.
		if (!this.ungenerated.isEmpty())
		{
			for (ChunkPos pos : this.ungenerated)
			{
				if (this.pendingChunks.remove(pos))
				{
					this.result.addUngeneratedChunk();
				}

				// Drop any ticket taken before we found out it was a dead end
				this.chunkLoader.release(pos);
			}

			this.ungenerated.clear();
		}

		if (this.pendingChunks.isEmpty())
		{
			this.finished = true;
			profiler.pop();
			return true;
		}

		// Nothing left to wait for: the remaining chunks are not loaded and nobody is
		// loading them. Report them instead of spinning forever. A tick spent waiting on
		// the TPS backoff or on an in-flight load is progress, not a stall - but the wait
		// is still bounded, so a permanently overloaded server cannot strand the task.
		boolean waiting = paused || (this.chunkLoader != null && this.chunkLoader.getPendingRequests() > 0);

		if (processedThisTick > 0 || budgetExhausted)
		{
			this.stuckTicks = 0;
			this.waitingTicks = 0;
		}
		else if (waiting)
		{
			this.stuckTicks = 0;

			if (++this.waitingTicks > WAITING_TICK_LIMIT)
			{
				return this.giveUp(profiler);
			}
		}
		else if (++this.stuckTicks > STUCK_TICK_LIMIT)
		{
			return this.giveUp(profiler);
		}

		profiler.pop();
		return false;
	}

	/** Stops early, reporting whatever is left as unread rather than spinning on it. */
	private boolean giveUp(ProfilerFiller profiler)
	{
		this.result.setUnloadedChunks(this.pendingChunks.size());
		this.pendingChunks.clear();
		this.finished = true;
		profiler.pop();

		return true;
	}

	@Override
	protected boolean processChunk(ChunkPos pos)
	{
		// New list to avoid CME
		ArrayList<SchematicPlacement> placements = new ArrayList<>(this.placementsPerChunk.get(pos));

		for (SchematicPlacement placement : placements)
		{
			SchematicVerifyUtils.verifyWorldWithinChunk(this.context.world(), pos, placement,
			                                            this.layerBehavior, this.layerRange,
			                                            this.result, this.nbtComparator);

			this.placementsPerChunk.remove(pos, placement);
		}

		this.result.addProcessedChunk();

		// Read and done: let go of the chunk so it can unload again
		if (this.chunkLoader != null)
		{
			this.chunkLoader.release(pos);
		}

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

		// However this task ended, it must not leave tickets behind holding chunks in memory
		if (this.chunkLoader != null)
		{
			this.chunkLoader.releaseAll();
		}

		// onStop() is already dispatched onto the server thread by the base class
		if (this.onComplete != null)
		{
			this.onComplete.run();
		}

		super.onStop();
	}
}
