package fi.dy.masa.servux.schematic.materials;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.scheduler.session.IResultBatcher;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.schematic.analyzer.AnalyzeResultSerializer;
import fi.dy.masa.servux.schematic.verifier.VerifyResultSerializer;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.ListData;
import fi.dy.masa.servux.util.data.tag.StringData;

/**
 * Turns a {@link MaterialListResult} into the batches that go over the wire.
 * <p>
 * Like {@link AnalyzeResultSerializer}, a material list is bounded by how many
 * <i>distinct</i> block states, entity types and item types a placement contains rather
 * than by its size, so in practice it always fits one batch. It is still cut on a budget,
 * because "in practice" is not a size limit and the acknowledge-to-pull machinery costs
 * nothing when there is only ever one batch.
 * <p>
 * The three block tallies share one palette and travel as three parallel count arrays.
 * Missing and mismatched are subsets of the total, so a state absent from either simply
 * gets a zero, and the client rebuilds the three maps Litematica's
 * {@code buildEntriesForPlacement()} expects by reading the arrays side by side.
 * <p>
 * The encoding follows the two decisions {@link VerifyResultSerializer} established: block
 * states travel as global palette ids, and item/entity types travel as registry
 * identifiers rather than numeric ids that shift with the mod set.
 */
public class MaterialListResultSerializer implements IResultBatcher
{
	private final MaterialListResult result;

	private final List<BlockState> blockStates = new ArrayList<>();
	private final List<Identifier> entityTypes = new ArrayList<>();
	private final List<Identifier> itemTypes = new ArrayList<>();

	private int blockIndex;
	private int entityIndex;
	private int itemIndex;
	private int batch;

	public MaterialListResultSerializer(MaterialListResult result)
	{
		this.result = result;

		this.blockStates.addAll(result.getCountsTotal().keySet());
		this.entityTypes.addAll(result.getEntityCounts().keySet());
		this.itemTypes.addAll(result.getContainerItemCounts().keySet());
	}

	@Override
	public boolean hasMore()
	{
		return this.blockIndex < this.blockStates.size()
		    || this.entityIndex < this.entityTypes.size()
		    || this.itemIndex < this.itemTypes.size();
	}

	@Override
	public int getBatchNumber()
	{
		return this.batch;
	}

	@Override
	public CompoundData nextBatch(int maxEntries, UUID sessionId)
	{
		CompoundData tag = new CompoundData();
		tag.putString("Task", ServerTaskKind.MATERIALS.resultTask());
		tag.putIntArray("SessionId", VerifyResultSerializer.uuidToIntArray(sessionId));
		tag.putInt("Batch", this.batch);

		int budget = Math.max(1, maxEntries);

		budget -= this.writeBlocks(tag, budget);
		budget -= this.writeEntities(tag, budget);
		this.writeItems(tag, budget);

		boolean last = !this.hasMore();
		tag.putBoolean("Final", last);

		if (last)
		{
			tag.put("Totals", this.writeTotals());
		}

		this.batch++;

		return tag;
	}

	/** @return how much of the budget was spent */
	private int writeBlocks(CompoundData tag, int budget)
	{
		int take = Math.min(budget, this.blockStates.size() - this.blockIndex);
		Object2IntOpenHashMap<BlockState> total = this.result.getCountsTotal();
		Object2IntOpenHashMap<BlockState> missing = this.result.getCountsMissing();
		Object2IntOpenHashMap<BlockState> mismatch = this.result.getCountsMismatch();

		int[] palette = new int[take];
		int[] totals = new int[take];
		int[] missings = new int[take];
		int[] mismatches = new int[take];

		for (int i = 0; i < take; i++)
		{
			BlockState state = this.blockStates.get(this.blockIndex + i);

			palette[i] = Block.getId(state);
			totals[i] = total.getInt(state);
			missings[i] = missing.getInt(state);
			mismatches[i] = mismatch.getInt(state);
		}

		this.blockIndex += take;

		tag.putIntArray("BlockPalette", palette);
		tag.putIntArray("BlockCountsTotal", totals);
		tag.putIntArray("BlockCountsMissing", missings);
		tag.putIntArray("BlockCountsMismatch", mismatches);

		return take;
	}

	/** @return how much of the budget was spent */
	private int writeEntities(CompoundData tag, int budget)
	{
		int take = Math.max(0, Math.min(budget, this.entityTypes.size() - this.entityIndex));
		Object2IntOpenHashMap<Identifier> counts = this.result.getEntityCounts();

		ListData ids = new ListData();
		int[] amounts = new int[take];

		for (int i = 0; i < take; i++)
		{
			Identifier id = this.entityTypes.get(this.entityIndex + i);

			ids.add(new StringData(id.toString()));
			amounts[i] = counts.getInt(id);
		}

		this.entityIndex += take;

		tag.put("EntityIds", ids);
		tag.putIntArray("EntityCounts", amounts);

		return take;
	}

	private void writeItems(CompoundData tag, int budget)
	{
		int take = Math.max(0, Math.min(budget, this.itemTypes.size() - this.itemIndex));
		Object2IntOpenHashMap<Identifier> counts = this.result.getContainerItemCounts();

		ListData ids = new ListData();
		int[] amounts = new int[take];

		for (int i = 0; i < take; i++)
		{
			Identifier id = this.itemTypes.get(this.itemIndex + i);

			ids.add(new StringData(id.toString()));
			amounts[i] = counts.getInt(id);
		}

		this.itemIndex += take;

		tag.put("ItemIds", ids);
		tag.putIntArray("ItemCounts", amounts);
	}

	private CompoundData writeTotals()
	{
		CompoundData totals = new CompoundData();

		totals.putLong("BlocksTotal", this.result.getBlocksTotal());
		totals.putLong("BlocksMissing", this.result.getBlocksMissing());
		totals.putLong("BlocksMismatched", this.result.getBlocksMismatched());
		totals.putInt("TotalChunks", this.result.getProgress().getTotalChunks());
		totals.putInt("ProcessedChunks", this.result.getProgress().getProcessedChunks());
		totals.putInt("UnloadedChunks", this.result.getProgress().getUnloadedChunks());
		totals.putInt("UngeneratedChunks", this.result.getProgress().getUngeneratedChunks());

		return totals;
	}
}
