package fi.dy.masa.servux.schematic.materials;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;

/**
 * Accumulates the outcome of a server side material list run.
 * <p>
 * The five tallies are exactly the ones Litematica's {@code TaskCountBlocksPlacement}
 * builds - {@code countsTotal}, {@code countsMissing}, {@code countsMismatch},
 * {@code entitiesTotal} and {@code containersTotal} - so a server result drops straight
 * into {@code MaterialListUtils.buildEntriesForPlacement()} on the client.
 * <p>
 * Only the first three depend on the world, and they are the whole point of doing this
 * server side: the client asks {@code clientWorld.getBlockState()} for every position in
 * the placement, and outside its render distance that answers air. A build bigger than the
 * render distance therefore reports as almost entirely missing, which is exactly the
 * material list a player is most likely to want. The server has no such blind spot.
 * <p>
 * Block states are counted rather than items: turning a state into the items it costs needs
 * Litematica's {@code MaterialCache}, which is built from a client side fake world and has
 * no server equivalent. The client already has it, so the conversion stays there.
 * <p>
 * Only ever touched from the server thread, so it is deliberately not synchronized.
 */
public class MaterialListResult
{
	private final ChunkWalkProgress progress = new ChunkWalkProgress();

	private final Object2IntOpenHashMap<BlockState> countsTotal = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<BlockState> countsMissing = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<BlockState> countsMismatch = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<Identifier> entityCounts = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<Identifier> containerItemCounts = new Object2IntOpenHashMap<>();

	private long blocksTotal;
	private long blocksMissing;
	private long blocksMismatched;

	public MaterialListResult()
	{
		this.countsTotal.defaultReturnValue(0);
		this.countsMissing.defaultReturnValue(0);
		this.countsMismatch.defaultReturnValue(0);
		this.entityCounts.defaultReturnValue(0);
		this.containerItemCounts.defaultReturnValue(0);
	}

	public ChunkWalkProgress getProgress()
	{
		return this.progress;
	}

	/** One non-air block the schematic asks for, whether or not it is already placed. */
	public void addTotal(BlockState state)
	{
		this.countsTotal.addTo(state, 1);
		this.blocksTotal++;
	}

	/** A block the schematic asks for that is not there yet, or is there as something else. */
	public void addMissing(BlockState state)
	{
		this.countsMissing.addTo(state, 1);
		this.blocksMissing++;
	}

	/**
	 * A block that is present but wrong. Always also counted as missing - the right block
	 * still has to be brought - so this count deliberately overlaps {@link #addMissing}.
	 */
	public void addMismatch(BlockState state)
	{
		this.countsMismatch.addTo(state, 1);
		this.blocksMismatched++;
	}

	public void addEntity(Identifier entityTypeId)
	{
		this.entityCounts.addTo(entityTypeId, 1);
	}

	public void addContainerItem(Identifier itemId, int count)
	{
		this.containerItemCounts.addTo(itemId, count);
	}

	public Object2IntOpenHashMap<BlockState> getCountsTotal()
	{
		return this.countsTotal;
	}

	public Object2IntOpenHashMap<BlockState> getCountsMissing()
	{
		return this.countsMissing;
	}

	public Object2IntOpenHashMap<BlockState> getCountsMismatch()
	{
		return this.countsMismatch;
	}

	public Object2IntOpenHashMap<Identifier> getEntityCounts()
	{
		return this.entityCounts;
	}

	public Object2IntOpenHashMap<Identifier> getContainerItemCounts()
	{
		return this.containerItemCounts;
	}

	/** Every non-air block the placement asks for. */
	public long getBlocksTotal()
	{
		return this.blocksTotal;
	}

	/** How many of those still have to be placed. */
	public long getBlocksMissing()
	{
		return this.blocksMissing;
	}

	/** How many of the missing ones have some other block in the way. */
	public long getBlocksMismatched()
	{
		return this.blocksMismatched;
	}

	/** How many distinct block states the placement asks for. */
	public int getDistinctBlockStates()
	{
		return this.countsTotal.size();
	}

	/** True when everything the placement asks for is already in the world. */
	public boolean isComplete()
	{
		return this.blocksMissing == 0 && this.progress.getSkippedChunks() == 0;
	}
}
