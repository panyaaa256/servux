package fi.dy.masa.servux.schematic.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

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
 */
public class VerifyResultSerializer
{
	private final VerifyResult result;

	/** The flattened pair list, so that batching can resume mid-pair. */
	private final List<Map.Entry<BlockMismatch, Collection<BlockPos>>> pairs = new ArrayList<>();

	private int pairIndex;
	private int positionIndex;
	private int batch;

	public VerifyResultSerializer(VerifyResult result)
	{
		this.result = result;
		this.pairs.addAll(result.getMismatches().asMap().entrySet());
	}

	public boolean hasMore()
	{
		return this.pairIndex < this.pairs.size();
	}

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
	public CompoundTag nextBatch(int maxPositions, java.util.UUID sessionId)
	{
		CompoundTag tag = new CompoundTag();
		tag.putString("Task", "LitematicaVerifyResult");
		tag.putIntArray("SessionId", uuidToIntArray(sessionId));
		tag.putInt("Batch", this.batch);

		// Palette local to this batch; the client resolves ids straight back to block states
		Object2IntOpenHashMap<BlockState> palette = new Object2IntOpenHashMap<>();
		palette.defaultReturnValue(-1);
		List<BlockState> paletteOrder = new ArrayList<>();

		ListTag entries = new ListTag();
		int budget = Math.max(1, maxPositions);

		while (budget > 0 && this.pairIndex < this.pairs.size())
		{
			Map.Entry<BlockMismatch, Collection<BlockPos>> entry = this.pairs.get(this.pairIndex);
			BlockMismatch mismatch = entry.getKey();
			List<BlockPos> positions = asList(entry.getValue());

			int remaining = positions.size() - this.positionIndex;
			int take = Math.min(remaining, budget);

			long[] encoded = new long[take];

			for (int i = 0; i < take; i++)
			{
				encoded[i] = positions.get(this.positionIndex + i).asLong();
			}

			CompoundTag element = new CompoundTag();
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

		int[] paletteIds = new int[paletteOrder.size()];

		for (int i = 0; i < paletteOrder.size(); i++)
		{
			paletteIds[i] = Block.getId(paletteOrder.get(i));
		}

		tag.putIntArray("StatePalette", paletteIds);
		tag.put("Entries", entries);

		boolean last = !this.hasMore();
		tag.putBoolean("Final", last);

		if (last)
		{
			tag.put("Totals", this.writeTotals());
		}

		this.batch++;

		return tag;
	}

	private CompoundTag writeTotals()
	{
		CompoundTag totals = new CompoundTag();

		totals.putInt("SchematicBlocks", this.result.getSchematicBlocks());
		totals.putInt("WorldBlocks", this.result.getWorldBlocks());
		totals.putInt("CorrectStatesCount", this.result.getCorrectStatesCount());
		totals.putInt("TotalChunks", this.result.getTotalChunks());
		totals.putInt("ProcessedChunks", this.result.getProcessedChunks());
		totals.putInt("UnloadedChunks", this.result.getUnloadedChunks());
		totals.putInt("UngeneratedChunks", this.result.getUngeneratedChunks());
		totals.putBoolean("Truncated", this.result.isTruncated());

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

	public static int[] uuidToIntArray(java.util.UUID uuid)
	{
		long most = uuid.getMostSignificantBits();
		long least = uuid.getLeastSignificantBits();

		return new int[] {(int) (most >> 32), (int) most, (int) (least >> 32), (int) least};
	}

	public static java.util.UUID uuidFromIntArray(int[] array)
	{
		if (array == null || array.length != 4)
		{
			return null;
		}

		return new java.util.UUID((long) array[0] << 32 | (array[1] & 0xFFFFFFFFL),
		                          (long) array[2] << 32 | (array[3] & 0xFFFFFFFFL));
	}
}
