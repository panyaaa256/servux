package fi.dy.masa.servux.scheduler.tasks;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;
import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.util.chunk.ServerChunkLoader;

/**
 * Walks a set of chunks across ticks, doing whatever the subclass does with each one.
 * <p>
 * Everything here is about being a well behaved guest on a live server, which is the part
 * that is identical no matter what the walk is <i>for</i> - verifying a placement, counting
 * an area's blocks, reading it into a schematic:
 * <ul>
 *   <li><b>Bounded per tick.</b> The walk stops as soon as the tick's time budget is spent,
 *       measured against what the tick has already cost, and resumes next tick.</li>
 *   <li><b>Backs off.</b> While the server is running hot, no new chunk loads are issued.
 *       Chunks already in memory keep being processed; only the extra I/O pauses.</li>
 *   <li><b>Bounded overall.</b> A chunk nobody is loading, or a server too busy to ever
 *       catch up, ends the walk with the remainder reported rather than stranding the task
 *       in the scheduler forever.</li>
 *   <li><b>Leaves nothing behind.</b> However the walk ends, every chunk ticket it took is
 *       released.</li>
 * </ul>
 * Subclasses supply {@link #processChunk(ChunkPos)} and populate {@code pendingChunks}
 * during {@link #init()}, typically via one of {@code addPerChunkBoxes()} or
 * {@link fi.dy.masa.servux.util.SchematicChunkPartitioner}.
 * <p>
 * The defaults here suit a task that <i>reads</i>: it needs only the chunk itself, it may
 * pull chunks in through a {@link ServerChunkLoader}, and a chunk it never gets to see is
 * reported rather than treated as a failure. A task that writes must override
 * {@link #getRequiredChunkRadius()} and must not be given a loader - see that method and
 * {@link ServerChunkLoader} for why.
 */
public abstract class TaskChunkWalkerBase extends TaskProcessChunkBase
{
	/**
	 * How many consecutive ticks without progress to tolerate before declaring the
	 * remaining chunks unreachable. Chunk loads driven by other players are asynchronous,
	 * so a short grace period avoids giving up on a chunk that is about to arrive.
	 */
	private static final int STUCK_TICK_LIMIT = 100;

	/**
	 * Upper bound on time spent waiting on chunk loads or on the TPS backoff, so that a
	 * permanently overloaded server ends the walk instead of stranding it. Five minutes at
	 * 20 ticks per second.
	 */
	private static final int WAITING_TICK_LIMIT = 6000;

	/** How often to emit a progress ping, in ticks. */
	private static final int PROGRESS_TICK_INTERVAL = 20;

	/** Nanoseconds a tick may reach before the walk yields the rest of it. */
	private static final long TICK_BUDGET_NANOS = 60000000L;

	protected final ChunkWalkProgress progress;
	@Nullable protected final ServerChunkLoader chunkLoader;

	private final int pauseMsptThreshold;
	private final List<ChunkPos> ungenerated = new ArrayList<>();

	@Nullable private Runnable onComplete;
	@Nullable private Runnable onProgress;

	private int ticksSinceProgress;
	private int stuckTicks;
	private int waitingTicks;
	private boolean cancelled;

	/**
	 * @param chunkLoader        pulls chunks in on demand, or null to only visit chunks
	 *                           that happen to be loaded already
	 * @param pauseMsptThreshold average tick time above which no new loads are issued;
	 *                           0 or less disables the backoff
	 */
	protected TaskChunkWalkerBase(TaskContext context,
	                              ChunkWalkProgress progress,
	                              @Nullable ServerChunkLoader chunkLoader,
	                              int pauseMsptThreshold,
	                              @Nullable Runnable onComplete)
	{
		super(context);

		this.progress = progress;
		this.chunkLoader = chunkLoader;
		this.pauseMsptThreshold = pauseMsptThreshold;
		this.onComplete = onComplete;
	}

	public ChunkWalkProgress getProgress()
	{
		return this.progress;
	}

	/** Runs on the server thread once the task stops, whether it finished or was cancelled. */
	public void setOnComplete(@Nullable Runnable onComplete)
	{
		this.onComplete = onComplete;
	}

	/**
	 * Runs periodically while the task is working, for progress reporting. Walking a large
	 * build takes a while, so the requester needs to see it moving.
	 */
	public void setOnProgress(@Nullable Runnable onProgress)
	{
		this.onProgress = onProgress;
	}

	/** Requests that the task stop at the next tick; whatever was collected is kept. */
	public void cancel()
	{
		this.cancelled = true;
	}

	public boolean isCancelled()
	{
		return this.cancelled;
	}

	/**
	 * How much of the chunk's neighbourhood has to be present before it can be processed.
	 * <p>
	 * Reading a chunk needs only the chunk itself. Writing to one needs its neighbours too,
	 * because the block updates a write triggers reach into them.
	 */
	protected int getRequiredChunkRadius()
	{
		return 0;
	}

	@Override
	public void init()
	{
		this.progress.setTotalChunks(this.pendingChunks.size());
	}

	@Override
	public boolean canExecute()
	{
		return super.canExecute() && this.context.level() != null && !this.cancelled;
	}

	/**
	 * With a loader attached this also drives the load: it asks the loader to bring the
	 * chunk in and returns true only once it is actually readable, so the caller reads it
	 * during the very tick it became available.
	 */
	@Override
	protected boolean canProcessChunk(ChunkPos pos)
	{
		if (this.areSurroundingChunksLoaded(pos, this.context.level(), this.getRequiredChunkRadius()))
		{
			return true;
		}

		if (this.chunkLoader == null)
		{
			return false;
		}

		ServerChunkLoader.Result result = this.chunkLoader.request(pos);

		if (result == ServerChunkLoader.Result.UNGENERATED)
		{
			// Nothing to work with, and generating it is not on the table
			this.ungenerated.add(pos);
		}

		return result == ServerChunkLoader.Result.READY;
	}

	/**
	 * True while the server is running hot enough that we should stop pulling new chunks
	 * in. Already loaded chunks keep being processed; only the extra I/O is paused.
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
		profiler.push("chunk_walk");

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

		// Back off from issuing new loads while the server is struggling; a walk is never
		// urgent enough to compete with the players on it. Chunks that are already in memory
		// keep being processed, only the extra I/O pauses. Without a loader there is no I/O
		// to pause, so the threshold does not apply at all.
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

			if (elapsedTickTime >= TICK_BUDGET_NANOS)
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

		this.dropUngeneratedChunks();

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
		// Evaluated unconditionally, since it also resets the subclass' per-tick state
		waiting |= this.isWaitingOnChunkData();

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

	/**
	 * Retires the chunks that turned out never to have been generated: there is nothing to
	 * read there, and generating them is exactly what a walk must not do.
	 */
	private void dropUngeneratedChunks()
	{
		if (this.ungenerated.isEmpty())
		{
			return;
		}

		for (ChunkPos pos : this.ungenerated)
		{
			if (this.pendingChunks.remove(pos))
			{
				this.progress.addUngeneratedChunk();
			}

			// Drop any ticket taken before we found out it was a dead end
			if (this.chunkLoader != null)
			{
				this.chunkLoader.release(pos);
			}
		}

		this.ungenerated.clear();
	}

	/** Stops early, reporting whatever is left as unread rather than spinning on it. */
	private boolean giveUp(ProfilerFiller profiler)
	{
		this.onGiveUp(this.pendingChunks);

		this.progress.setUnloadedChunks(this.pendingChunks.size());
		this.pendingChunks.clear();
		this.finished = true;
		profiler.pop();

		return true;
	}

	/**
	 * Whether a chunk this tick was held back only because something the subclass needs from
	 * it is still on its way, such as its entities, which load after its blocks. A wait like
	 * that is not a stall; it is bounded the same way as waiting on a chunk load.
	 * <p>
	 * Called once per tick, after the tick's chunks have been tried.
	 */
	protected boolean isWaitingOnChunkData()
	{
		return false;
	}

	/**
	 * Called with the chunks that will not be visited, just before the walk gives up on
	 * them. The list must not be retained; it is cleared immediately afterwards.
	 */
	protected void onGiveUp(List<ChunkPos> remaining)
	{
	}

	@Override
	protected void onStop()
	{
		if (!this.finished && !this.cancelled)
		{
			// Aborted for a reason other than an explicit cancel (world unloaded, etc.)
			this.progress.setUnloadedChunks(this.pendingChunks.size());
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
