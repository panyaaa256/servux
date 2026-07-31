package fi.dy.masa.servux.schematic.verifier;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

import fi.dy.masa.servux.Servux;

/**
 * Brings the chunks a verification needs into memory, without ever stalling the server
 * thread and without generating terrain.
 * <p>
 * This is the part of verification that touches a live server hardest, so every step is
 * deliberately conservative:
 * <ul>
 *   <li><b>Never blocks.</b> {@code getChunk(..., create = true)} parks the server thread
 *       in {@code managedBlock()} until the chunk arrives, which every player on the
 *       server pays for. Loading here is driven by futures and polled across ticks
 *       instead, so a slow disk costs throughput but never latency.</li>
 *   <li><b>Never generates.</b> Loading a chunk that does not exist yet would run world
 *       gen - hundreds of milliseconds, blowing straight through the per-tick budget
 *       (which is only checked between chunks), and permanently enlarging the world as a
 *       side effect of merely inspecting it. Unless generation is explicitly opted into,
 *       every candidate is first probed against the region files and reported as
 *       ungenerated if absent.</li>
 *   <li><b>Never simulates.</b> The ticket carries {@code FLAG_LOADING} <i>without</i>
 *       {@code FLAG_SIMULATION}, so the chunks it pulls in are readable but not ticked:
 *       no mob spawning, no redstone, no block or random ticks. Verification observes the
 *       world, it does not run it.</li>
 *   <li><b>Never leaks.</b> Tickets are released as soon as a chunk has been read, and
 *       carry a timeout as a backstop in case the task dies without cleaning up.</li>
 * </ul>
 */
public class VerifyChunkLoader
{
	/** Ticks a verify ticket survives untouched; a backstop, not the normal release path. */
	private static final long TICKET_TIMEOUT_TICKS = 200L;

	/**
	 * Loading but explicitly not simulating. This is what keeps a verification from
	 * waking up the machinery in the chunks it visits.
	 */
	private static final TicketType VERIFY_TICKET = new TicketType(TICKET_TIMEOUT_TICKS, TicketType.FLAG_LOADING);

	/** Radius 0: the ticket covers only the chunk being read. */
	private static final int TICKET_RADIUS = 0;

	public enum Result
	{
		/** The chunk is loaded and must be read during this tick. */
		READY,
		/** Still loading, or deferred by the per-tick budget; try again next tick. */
		PENDING,
		/** No such chunk exists on disk and generating it was not permitted. */
		UNGENERATED
	}

	private enum Stage
	{
		/** Asking the region files whether this chunk has ever been generated. */
		PROBE,
		/** Ticket added, waiting for the chunk to come into memory. */
		LOAD
	}

	private static final class Request
	{
		private Stage stage;
		private CompletableFuture<Optional<CompoundTag>> probe;
		private CompletableFuture<?> load;

		private boolean isDone()
		{
			return this.stage == Stage.PROBE ? this.probe.isDone() : this.load.isDone();
		}
	}

	private final ServerLevel world;
	private final boolean generateMissing;
	private final int maxNewRequestsPerTick;

	private final Map<ChunkPos, Request> requests = new HashMap<>();
	private final Set<ChunkPos> ticketed = new HashSet<>();

	private int newRequestsThisTick;

	public VerifyChunkLoader(ServerLevel world, boolean generateMissing, int maxNewRequestsPerTick)
	{
		this.world = world;
		this.generateMissing = generateMissing;
		this.maxNewRequestsPerTick = Math.max(1, maxNewRequestsPerTick);
	}

	/**
	 * Resets the per-tick request budget; call once at the start of each tick.
	 *
	 * @param allowNewRequests false to issue no new loads this tick (TPS backoff). Loads
	 *                         already in flight still complete, so nothing is stranded.
	 */
	public void startTick(boolean allowNewRequests)
	{
		this.newRequestsThisTick = allowNewRequests ? 0 : this.maxNewRequestsPerTick;
	}

	public int getPendingRequests()
	{
		return this.requests.size();
	}

	/**
	 * Drives one chunk towards being readable.
	 * <p>
	 * Must be called from the server thread. A {@link Result#READY} chunk has to be read
	 * in the same tick, before the chunk system gets a chance to unload it again.
	 */
	public Result request(ChunkPos pos)
	{
		ServerChunkCache cache = this.world.getChunkSource();

		if (cache.hasChunk(pos.x(), pos.z()))
		{
			return Result.READY;
		}

		Request request = this.requests.get(pos);

		if (request == null)
		{
			return this.begin(pos, cache);
		}

		if (!request.isDone())
		{
			return Result.PENDING;
		}

		if (request.stage == Stage.PROBE)
		{
			return this.onProbeFinished(pos, cache, request);
		}

		// The load finished. If the chunk still is not there the ticket did not reach a
		// full status; report it rather than spinning on it.
		this.requests.remove(pos);

		return cache.hasChunk(pos.x(), pos.z()) ? Result.READY : Result.UNGENERATED;
	}

	private Result begin(ChunkPos pos, ServerChunkCache cache)
	{
		if (this.newRequestsThisTick >= this.maxNewRequestsPerTick)
		{
			return Result.PENDING;
		}

		this.newRequestsThisTick++;

		Request request = new Request();

		if (this.generateMissing)
		{
			this.startLoad(pos, cache, request);
		}
		else
		{
			request.stage = Stage.PROBE;
			request.probe = cache.chunkMap.read(pos);
		}

		this.requests.put(pos, request);

		return Result.PENDING;
	}

	private Result onProbeFinished(ChunkPos pos, ServerChunkCache cache, Request request)
	{
		Optional<CompoundTag> stored;

		try
		{
			stored = request.probe.join();
		}
		catch (Exception e)
		{
			Servux.LOGGER.warn("VerifyChunkLoader: failed to probe chunk {}; treating it as ungenerated; {}", pos, e.getLocalizedMessage());
			this.requests.remove(pos);

			return Result.UNGENERATED;
		}

		if (stored.isEmpty())
		{
			// Never generated. Loading it would create terrain that did not exist before.
			this.requests.remove(pos);

			return Result.UNGENERATED;
		}

		this.startLoad(pos, cache, request);

		return Result.PENDING;
	}

	private void startLoad(ChunkPos pos, ServerChunkCache cache, Request request)
	{
		request.stage = Stage.LOAD;
		request.load = cache.addTicketAndLoadWithRadius(VERIFY_TICKET, pos, TICKET_RADIUS);

		this.ticketed.add(pos);
	}

	/** Drops the ticket for a chunk that has been read, letting it unload again. */
	public void release(ChunkPos pos)
	{
		this.requests.remove(pos);

		if (this.ticketed.remove(pos))
		{
			this.world.getChunkSource().removeTicketWithRadius(VERIFY_TICKET, pos, TICKET_RADIUS);
		}
	}

	/** Releases every ticket still held; call when the task stops, however it stops. */
	public void releaseAll()
	{
		ServerChunkCache cache = this.world.getChunkSource();

		for (ChunkPos pos : this.ticketed)
		{
			cache.removeTicketWithRadius(VERIFY_TICKET, pos, TICKET_RADIUS);
		}

		this.ticketed.clear();
		this.requests.clear();
	}
}
