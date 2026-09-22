package fi.dy.masa.servux.schematic.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.scheduler.session.IResultBatcher;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.ListData;

/**
 * Turns a {@link VerifyResult} into the batches that go over the wire.
 * <p>
 * Batching is a cursor over the flattened (pair, position) list, so a result of any size
 * can be handed over a bounded amount at a time: the client acknowledges each batch and
 * the server only then produces the next one. Nothing is buffered up front.
 * <p>
 * Two encoding decisions are worth spelling out.
 * <p>
 * <b>Block states travel as global palette ids.</b> {@link Block#getId(BlockState)} is the
 * same numbering vanilla chunk packets use, so the client resolves each id back to the
 * canonical {@code BlockState} instance. That matters because Litematica's mismatch
 * bookkeeping and its "ignored pairs" set are keyed on state identity.
 * <p>
 * <b>Categories travel as names, not ordinals.</b> The original design sent the ordinal of
 * Litematica's {@code MismatchType} directly, which only works while both enums stay in
 * lockstep - and they do not: forks add their own categories (an entity mismatch category,
 * for instance) part way through the enum, which silently shifts every later value. A short
 * name per distinct pair costs nothing measurable and cannot be misread.
 * <p>
 * Missing entities follow the block pairs, in a list of their own ({@code Entities}): an
 * entity is not a block state pair, and its position is not on the block grid. Each one
 * costs one position of the batch budget.
 */
public class VerifyResultSerializer implements IResultBatcher
{
	/** Wrong Contents positions per batch that carry both sides' container data. */
	private static final int MAX_CONTENTS_PER_BATCH = 256;

	private final VerifyResult result;

	/** The flattened pair list, so that batching can resume mid-pair. */
	private final List<Map.Entry<BlockMismatch, Collection<BlockPos>>> pairs = new ArrayList<>();

	private final List<VerifyResult.MissingEntity> entities;

	private int pairIndex;
	private int positionIndex;
	private int entityIndex;
	private int batch;

	public VerifyResultSerializer(VerifyResult result)
	{
		this.result = result;
		this.pairs.addAll(result.getMismatches().asMap().entrySet());
		this.entities = result.getMissingEntities();
	}

	@Override
	public boolean hasMore()
	{
		return this.pairIndex < this.pairs.size() || this.entityIndex < this.entities.size();
	}

	@Override
	public int getBatchNumber()
	{
		return this.batch;
	}

	/**
	 * Produces the next batch, holding at most {@code maxPositions} positions.
	 * <p>
	 * The final batch additionally carries the run's totals, so the client knows the
	 * stream is complete and can fill in the summary counters in one go.
	 */
	@Override
	public CompoundData nextBatch(int maxPositions, UUID sessionId)
	{
		CompoundData tag = new CompoundData();
		tag.putString("Task", "LitematicaVerifyResult");
		tag.putIntArray("SessionId", uuidToIntArray(sessionId));
		tag.putInt("Batch", this.batch);

		// Palette local to this batch; the client resolves ids straight back to block states
		Object2IntOpenHashMap<BlockState> palette = new Object2IntOpenHashMap<>();
		palette.defaultReturnValue(-1);
		List<BlockState> paletteOrder = new ArrayList<>();

		ListData entries = new ListData();
		ListData contents = new ListData();
		int budget = Math.max(1, maxPositions);
		// Container data is far heavier than a position, so it gets a budget of its own
		int contentsBudget = MAX_CONTENTS_PER_BATCH;

		while (budget > 0 && contentsBudget > 0 && this.pairIndex < this.pairs.size())
		{
			Map.Entry<BlockMismatch, Collection<BlockPos>> entry = this.pairs.get(this.pairIndex);
			BlockMismatch mismatch = entry.getKey();
			List<BlockPos> positions = asList(entry.getValue());
			final boolean withContents = mismatch.type() == VerifyMismatchType.WRONG_NBT && this.result.hasContents();

			int remaining = positions.size() - this.positionIndex;
			int take = Math.min(remaining, withContents ? Math.min(budget, contentsBudget) : budget);

			long[] encoded = new long[take];

			for (int i = 0; i < take; i++)
			{
				BlockPos pos = positions.get(this.positionIndex + i);
				encoded[i] = pos.asLong();

				VerifyResult.ContentsDetail detail = withContents ? this.result.getContents(pos) : null;

				if (detail != null)
				{
					CompoundData element = new CompoundData();
					element.putLong("Pos", pos.asLong());
					element.put("Expected", detail.expected());
					element.put("Found", detail.found());
					contents.add(element);
					contentsBudget--;
				}
			}

			CompoundData element = new CompoundData();
			element.putString("Type", mismatch.type().getName());
			element.putInt("Expected", paletteIndex(palette, paletteOrder, mismatch.expected()));
			element.putInt("Found", paletteIndex(palette, paletteOrder, mismatch.found()));
			element.putLongArray("Positions", encoded);
			entries.add(element);

			this.positionIndex += take;
			budget -= take;

			if (this.positionIndex >= positions.size())
			{
				this.pairIndex++;
				this.positionIndex = 0;
			}
		}

		ListData entities = new ListData();

		// Only once every block pair is out, so that a batch never has to be read out of order
		while (budget > 0 && this.pairIndex >= this.pairs.size() && this.entityIndex < this.entities.size())
		{
			VerifyResult.MissingEntity entity = this.entities.get(this.entityIndex++);
			CompoundData element = new CompoundData();

			element.putString("Type", entity.entityId());
			element.putDouble("X", entity.pos().x);
			element.putDouble("Y", entity.pos().y);
			element.putDouble("Z", entity.pos().z);
			entities.add(element);
			budget--;
		}

		int[] paletteIds = new int[paletteOrder.size()];

		for (int i = 0; i < paletteOrder.size(); i++)
		{
			paletteIds[i] = Block.getId(paletteOrder.get(i));
		}

		tag.putIntArray("StatePalette", paletteIds);
		tag.put("Entries", entries);

		if (contents.isEmpty() == false)
		{
			tag.put("Contents", contents);
		}

		if (entities.isEmpty() == false)
		{
			tag.put("Entities", entities);
		}

		boolean last = !this.hasMore();
		tag.putBoolean("Final", last);

		if (last)
		{
			tag.put("Totals", this.writeTotals());
		}

		this.batch++;

		return tag;
	}

	private CompoundData writeTotals()
	{
		CompoundData totals = new CompoundData();

		totals.putInt("SchematicBlocks", this.result.getSchematicBlocks());
		totals.putInt("WorldBlocks", this.result.getWorldBlocks());
		totals.putInt("CorrectStatesCount", this.result.getCorrectStatesCount());
		totals.putInt("TotalChunks", this.result.getTotalChunks());
		totals.putInt("ProcessedChunks", this.result.getProcessedChunks());
		totals.putInt("UnloadedChunks", this.result.getUnloadedChunks());
		totals.putInt("UngeneratedChunks", this.result.getUngeneratedChunks());
		totals.putBoolean("Truncated", this.result.isTruncated());
		totals.putBoolean("ContentsSlotExact", this.result.isContentsSlotExact());
		totals.putBoolean("ContentsStrict", this.result.isContentsStrict());
		totals.putBoolean("EntitiesChecked", this.result.isEntitiesChecked());
		totals.putInt("MissingEntities", this.result.getCategoryCount(VerifyMismatchType.MISSING_ENTITY));

		// The per-state correct counts, so the client can show the Correct State category
		Object2IntOpenHashMap<BlockState> correct = this.result.getCorrectStateCounts();
		int[] states = new int[correct.size()];
		int[] counts = new int[correct.size()];
		int i = 0;

		for (Object2IntOpenHashMap.Entry<BlockState> entry : correct.object2IntEntrySet())
		{
			states[i] = Block.getId(entry.getKey());
			counts[i] = entry.getIntValue();
			i++;
		}

		totals.putIntArray("CorrectStates", states);
		totals.putIntArray("CorrectStateCounts", counts);

		return totals;
	}

	private static int paletteIndex(Object2IntOpenHashMap<BlockState> palette, List<BlockState> order, BlockState state)
	{
		int existing = palette.getInt(state);

		if (existing >= 0)
		{
			return existing;
		}

		int index = order.size();
		palette.put(state, index);
		order.add(state);

		return index;
	}

	private static List<BlockPos> asList(Collection<BlockPos> positions)
	{
		return positions instanceof List<BlockPos> list ? list : new ArrayList<>(positions);
	}

	public static int[] uuidToIntArray(UUID uuid)
	{
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();

		return new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
	}

	@Nullable
	public static UUID uuidFromIntArray(@Nullable int[] array)
	{
		if (array == null || array.length != 4)
		{
			return null;
		}

		return new UUID((long) array[0] << 32 | (array[1] & 0xFFFFFFFFL),
		                (long) array[2] << 32 | (array[3] & 0xFFFFFFFFL));
	}
}
