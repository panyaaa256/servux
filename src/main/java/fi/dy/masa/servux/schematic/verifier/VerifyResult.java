package fi.dy.masa.servux.schematic.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;

/**
 * Accumulates the outcome of a server side verification run.
 * <p>
 * The counters mirror the values Litematica's own {@code SchematicVerifier} exposes to its
 * GUI, so that a server result and a client result of the same placement can be compared
 * number for number: {@link #getSchematicBlocks()} is {@code getSchematicTotalBlocks()},
 * {@link #getWorldBlocks()} is {@code getRealWorldTotalBlocks()},
 * {@link #getCorrectStatesCount()} is {@code getCorrectStatesCount()}, and
 * {@link #getUnloadedChunks()} is {@code getUnseenChunks()}.
 * <p>
 * Note that this class is only ever touched from the server thread (the verify task runs
 * inside the tick loop), so it is deliberately not synchronized.
 */
public class VerifyResult
{
	private final ArrayListMultimap<BlockMismatch, BlockPos> mismatches = ArrayListMultimap.create();
	private final Object2IntOpenHashMap<BlockState> correctStateCounts = new Object2IntOpenHashMap<>();
	private final int[] categoryCounts = new int[VerifyMismatchType.values().length];

	/**
	 * The chunk counters, kept in the shared holder the walking task maintains. Every
	 * chunk level getter below simply forwards to it, so the numbers this class reports
	 * are the same objects the task is updating.
	 */
	private final ChunkWalkProgress progress = new ChunkWalkProgress();

	private final int maxPositions;

	private int schematicBlocks;
	private int worldBlocks;
	private int correctStatesCount;
	private int storedPositions;
	private boolean truncated;

	public VerifyResult(int maxPositions)
	{
		this.maxPositions = maxPositions;
		this.correctStateCounts.defaultReturnValue(0);
	}

	/**
	 * Records one classified mismatch.
	 * <p>
	 * Once {@code maxPositions} positions have been stored the per-position lists stop
	 * growing, but the category counters keep going, so the reported totals stay accurate
	 * even for a truncated result.
	 */
	public void add(VerifyMismatchType type, BlockState expected, BlockState found, BlockPos pos)
	{
		this.categoryCounts[type.ordinal()]++;

		if (this.storedPositions >= this.maxPositions)
		{
			this.truncated = true;
			return;
		}

		this.mismatches.put(new BlockMismatch(type, expected, found), pos.immutable());
		this.storedPositions++;
	}

	public void addCorrectState(BlockState state, boolean countsTowardsSchematic)
	{
		this.correctStateCounts.addTo(state, 1);

		if (countsTowardsSchematic)
		{
			this.correctStatesCount++;
		}
	}

	public void addSchematicBlock()
	{
		this.schematicBlocks++;
	}

	public void addWorldBlock()
	{
		this.worldBlocks++;
	}

	public Multimap<BlockMismatch, BlockPos> getMismatches()
	{
		return this.mismatches;
	}

	/** The mismatch pairs of one category, ordered by descending position count. */
	public List<Map.Entry<BlockMismatch, Collection<BlockPos>>> getMismatchesFor(VerifyMismatchType type)
	{
		List<Map.Entry<BlockMismatch, Collection<BlockPos>>> list = new ArrayList<>();

		for (Map.Entry<BlockMismatch, Collection<BlockPos>> entry : this.mismatches.asMap().entrySet())
		{
			if (entry.getKey().type() == type)
			{
				list.add(entry);
			}
		}

		list.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

		return list;
	}

	public int getCategoryCount(VerifyMismatchType type)
	{
		return this.categoryCounts[type.ordinal()];
	}

	public int getTotalMismatches()
	{
		int total = 0;

		for (VerifyMismatchType type : VerifyMismatchType.REPORTED)
		{
			total += this.categoryCounts[type.ordinal()];
		}

		return total;
	}

	public Object2IntOpenHashMap<BlockState> getCorrectStateCounts()
	{
		return this.correctStateCounts;
	}

	public int getSchematicBlocks()
	{
		return this.schematicBlocks;
	}

	public int getWorldBlocks()
	{
		return this.worldBlocks;
	}

	public int getCorrectStatesCount()
	{
		return this.correctStatesCount;
	}

	/** The counters the walking task maintains; hand this to the task that fills it. */
	public ChunkWalkProgress getProgress()
	{
		return this.progress;
	}

	public int getTotalChunks()
	{
		return this.progress.getTotalChunks();
	}

	public int getProcessedChunks()
	{
		return this.progress.getProcessedChunks();
	}

	/** Chunks that were part of the placement but could not be read (not loaded). */
	public int getUnloadedChunks()
	{
		return this.progress.getUnloadedChunks();
	}

	/**
	 * Chunks the placement covers that have never been generated. These are skipped
	 * rather than generated, so that inspecting a build never enlarges the world.
	 */
	public int getUngeneratedChunks()
	{
		return this.progress.getUngeneratedChunks();
	}

	/** Chunks that were not read for any reason, whether unloaded or never generated. */
	public int getSkippedChunks()
	{
		return this.progress.getSkippedChunks();
	}

	/** True when the position lists were capped and do not hold every mismatch position. */
	public boolean isTruncated()
	{
		return this.truncated;
	}

	public boolean isPerfectMatch()
	{
		return this.getTotalMismatches() == 0 && this.getSkippedChunks() == 0;
	}
}
