package fi.dy.masa.servux.schematic.analyzer;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;

/**
 * Accumulates the outcome of a server side area analysis.
 * <p>
 * The three tallies mirror what Litematica's own area analyzer builds
 * ({@code countsTotal}, {@code entitiesTotal}, {@code containersTotal}), so a server result
 * drops straight into the same material list GUI.
 * <p>
 * The container tally is the reason this is worth doing server side at all: the client is
 * never told the contents of a container it has not opened, so on a multiplayer server its
 * own analyzer reports every chest as empty. The server has no such blind spot.
 * <p>
 * Only ever touched from the server thread, so it is deliberately not synchronized.
 */
public class AnalyzeResult
{
	private final ChunkWalkProgress progress = new ChunkWalkProgress();

	private final Object2IntOpenHashMap<BlockState> blockCounts = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<Identifier> entityCounts = new Object2IntOpenHashMap<>();
	private final Object2IntOpenHashMap<Identifier> containerItemCounts = new Object2IntOpenHashMap<>();

	private long blocksCounted;

	public AnalyzeResult()
	{
		this.blockCounts.defaultReturnValue(0);
		this.entityCounts.defaultReturnValue(0);
		this.containerItemCounts.defaultReturnValue(0);
	}

	public ChunkWalkProgress getProgress()
	{
		return this.progress;
	}

	public void addBlock(BlockState state)
	{
		this.blockCounts.addTo(state, 1);
		this.blocksCounted++;
	}

	public void addEntity(Identifier entityTypeId)
	{
		this.entityCounts.addTo(entityTypeId, 1);
	}

	public void addContainerItem(Identifier itemId, int count)
	{
		this.containerItemCounts.addTo(itemId, count);
	}

	public Object2IntOpenHashMap<BlockState> getBlockCounts()
	{
		return this.blockCounts;
	}

	public Object2IntOpenHashMap<Identifier> getEntityCounts()
	{
		return this.entityCounts;
	}

	public Object2IntOpenHashMap<Identifier> getContainerItemCounts()
	{
		return this.containerItemCounts;
	}

	/** Every block position visited, air included; the volume actually covered. */
	public long getBlocksCounted()
	{
		return this.blocksCounted;
	}

	/** How many distinct block states were seen. */
	public int getDistinctBlockStates()
	{
		return this.blockCounts.size();
	}
}
