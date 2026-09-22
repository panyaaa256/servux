package fi.dy.masa.servux.schematic.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nullable;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;
import fi.dy.masa.servux.util.data.tag.CompoundData;

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
	private final int maxContents;
	/** Both sides' block entity data at a Wrong Contents position, for the first maxContents of them. */
	private final Map<BlockPos, ContentsDetail> contents = new HashMap<>();
	private boolean contentsSlotExact;
	private boolean contentsStrict;
	/** Schematic entities with no counterpart in the world, in the order they were found. */
	private final List<MissingEntity> missingEntities = new ArrayList<>();
	private boolean entitiesChecked;

	private int schematicBlocks;
	private int worldBlocks;
	private int correctStatesCount;
	private int storedPositions;
	private boolean truncated;

	public VerifyResult(int maxPositions)
	{
		this(maxPositions, 0);
	}

	public VerifyResult(int maxPositions, int maxContents)
	{
		this.maxPositions = maxPositions;
		this.maxContents = maxContents;
		this.correctStateCounts.defaultReturnValue(0);
	}

	/**
	 * Records one classified mismatch.
	 * <p>
	 * Once {@code maxPositions} positions have been stored the per-position lists stop
	 * growing, but the category counters keep going, so the reported totals stay accurate
	 * even for a truncated result.
	 */
	public boolean add(VerifyMismatchType type, BlockState expected, BlockState found, BlockPos pos)
	{
		this.categoryCounts[type.ordinal()]++;

		if (this.storedPositions >= this.maxPositions)
		{
			this.truncated = true;
			return false;
		}

		this.mismatches.put(new BlockMismatch(type, expected, found), pos.immutable());
		this.storedPositions++;

		return true;
	}

	/**
	 * Records a Wrong Contents mismatch, keeping both sides' block entity data while there is
	 * room for it, so that the client can show the two inventories side by side.
	 */
	public void addWrongContents(BlockState expected, BlockState found, BlockPos pos,
	                             @Nullable CompoundData expectedData, CompoundData foundData)
	{
		if (this.add(VerifyMismatchType.WRONG_NBT, expected, found, pos) &&
			expectedData != null && this.contents.size() < this.maxContents)
		{
			this.contents.put(pos.immutable(), new ContentsDetail(expectedData, foundData));
		}
	}

	@Nullable
	public ContentsDetail getContents(BlockPos pos)
	{
		return this.contents.get(pos);
	}

	public boolean hasContents()
	{
		return this.contents.isEmpty() == false;
	}

	/** How the contents were compared, which the client needs to point out the same differences. */
	public void setContentsComparison(boolean slotExact, boolean strict)
	{
		this.contentsSlotExact = slotExact;
		this.contentsStrict = strict;
	}

	public boolean isContentsSlotExact()
	{
		return this.contentsSlotExact;
	}

	public boolean isContentsStrict()
	{
		return this.contentsStrict;
	}

	/**
	 * Records a schematic entity that has no counterpart in the world.
	 * <p>
	 * Shares the position cap with the block mismatches: past it the entity is still
	 * counted, it just is not listed.
	 */
	public void addMissingEntity(String entityId, Vec3 pos)
	{
		this.categoryCounts[VerifyMismatchType.MISSING_ENTITY.ordinal()]++;

		if (this.storedPositions >= this.maxPositions)
		{
			this.truncated = true;
			return;
		}

		this.missingEntities.add(new MissingEntity(entityId, pos));
		this.storedPositions++;
	}

	public List<MissingEntity> getMissingEntities()
	{
		return this.missingEntities;
	}

	/** Whether this run compared entities at all, as opposed to finding none missing. */
	public void setEntitiesChecked(boolean checked)
	{
		this.entitiesChecked = checked;
	}

	public boolean isEntitiesChecked()
	{
		return this.entitiesChecked;
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

	/** A schematic entity that is not in the world: its registry id and where it should be. */
	public record MissingEntity(String entityId, Vec3 pos)
	{
	}

	/** One side each of a container whose contents do not match: the schematic's, and the world's. */
	public record ContentsDetail(CompoundData expected, CompoundData found)
	{
	}
}
