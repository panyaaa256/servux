package fi.dy.masa.servux.scheduler.tasks;

import java.util.List;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableList;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.schematic.analyzer.AnalyzeResult;
import fi.dy.masa.servux.schematic.selection.Box;
import fi.dy.masa.servux.util.InventoryUtils;
import fi.dy.masa.servux.util.chunk.ServerChunkLoader;
import fi.dy.masa.servux.util.EntityUtils;
import fi.dy.masa.servux.util.IntBoundingBox;
import fi.dy.masa.servux.util.LayerRange;

/**
 * Walks an area chunk by chunk and tallies what is in it.
 * <p>
 * The block walk is the same one Litematica's own area analyzer does. What the client
 * cannot do is the other two tallies: it is never told the contents of a container it has
 * not opened, and it only sees entities inside its render distance, so on a multiplayer
 * server its analyzer reports every chest as empty and misses whatever is loaded but far
 * away. Reading them here is the point of the exercise.
 */
public class TaskAnalyzeArea extends TaskChunkWalkerBase
{
	private final ImmutableList<Box> boxes;
	private final LayerRange layerRange;
	private final AnalyzeResult result;
	private final boolean countEntities;
	private final boolean countContainers;

	public TaskAnalyzeArea(TaskContext context,
	                       List<Box> boxes,
	                       @Nullable LayerRange range,
	                       AnalyzeResult result,
	                       @Nullable ServerChunkLoader chunkLoader,
	                       boolean countEntities,
	                       boolean countContainers,
	                       int pauseMsptThreshold,
	                       @Nullable Runnable onComplete)
	{
		super(context, result.getProgress(), chunkLoader, pauseMsptThreshold, onComplete);

		this.boxes = ImmutableList.copyOf(boxes);
		this.layerRange = range != null ? range : new LayerRange();
		this.result = result;
		this.countEntities = countEntities;
		this.countContainers = countContainers;
	}

	public AnalyzeResult getResult()
	{
		return this.result;
	}

	@Override
	public void init()
	{
		// Fills boxesInChunks and pendingChunks, clamped to the layer range and world height
		this.addPerChunkBoxes(this.boxes, this.layerRange);

		super.init();
	}

	@Override
	protected boolean processChunk(ChunkPos pos)
	{
		ServerLevel world = this.context.level();

		this.countBlocksInChunk(world, pos);

		if (this.countEntities || this.countContainers)
		{
			this.countEntitiesInChunk(world, pos);
		}

		this.progress.addProcessedChunk();

		// Read and done: let go of the chunk so it can unload again
		if (this.chunkLoader != null)
		{
			this.chunkLoader.release(pos);
		}

		return true;
	}

	private void countBlocksInChunk(ServerLevel world, ChunkPos pos)
	{
		BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();

		for (IntBoundingBox bb : this.getBoxesInChunk(pos))
		{
			for (int y = bb.minY(); y <= bb.maxY(); ++y)
			{
				for (int z = bb.minZ(); z <= bb.maxZ(); ++z)
				{
					for (int x = bb.minX(); x <= bb.maxX(); ++x)
					{
						posMutable.set(x, y, z);

						BlockState state = world.getBlockState(posMutable);
						this.result.addBlock(state);

						if (this.countContainers && state.hasBlockEntity())
						{
							this.countBlockEntityContents(world, posMutable);
						}
					}
				}
			}
		}
	}

	private void countBlockEntityContents(ServerLevel world, BlockPos pos)
	{
		BlockEntity blockEntity = world.getBlockEntity(pos);

		if (blockEntity instanceof Container container)
		{
			this.addContainerItems(container);
		}
	}

	private void countEntitiesInChunk(ServerLevel world, ChunkPos pos)
	{
		for (IntBoundingBox bb : this.getBoxesInChunk(pos))
		{
			AABB aabb = new AABB(bb.minX(), bb.minY(), bb.minZ(), bb.maxX() + 1, bb.maxY() + 1, bb.maxZ() + 1);
			List<Entity> entities = world.getEntities((Entity) null, aabb, EntityUtils.NOT_PLAYER);

			for (Entity entity : entities)
			{
				if (this.countEntities)
				{
					Identifier id = EntityType.getKey(entity.getType());

					if (id != null)
					{
						this.result.addEntity(id);
					}
				}

				// Minecarts with chests, hoppers and so on hold items just like a block does
				if (this.countContainers && entity instanceof Container container)
				{
					this.addContainerItems(container);
				}
			}
		}
	}

	private void addContainerItems(Container container)
	{
		final int slots = container.getContainerSize();

		for (int slot = 0; slot < slots; ++slot)
		{
			this.addItem(container.getItem(slot));
		}
	}

	/**
	 * Counts one item, descending into shulker boxes and bundles.
	 * <p>
	 * A <i>full</i> shulker box or bundle contributes its contents and not itself, matching
	 * Litematica's own counting: a materials list is asking what is stored, not what it is
	 * stored in. An empty one has no contents to report, so it counts as the item it is.
	 * <p>
	 * On this version a real stack in a slot and the contents of a container component are
	 * both {@link ItemStack}s, which is what makes the recursion through nested containers a
	 * single method.
	 */
	private void addItem(ItemStack item)
	{
		// Covers both an empty slot and a stack whose count ran to zero
		if (item.getCount() <= 0)
		{
			return;
		}

		if (InventoryUtils.isShulkerBox(item) && InventoryUtils.shulkerBoxHasItems(item))
		{
			ItemContainerContents contents = item.get(DataComponents.CONTAINER);

			if (contents != null)
			{
				contents.nonEmptyItems().forEach(this::addItem);
				return;
			}
		}
		else if (InventoryUtils.isBundle(item) && InventoryUtils.bundleHasItems(item))
		{
			BundleContents contents = item.get(DataComponents.BUNDLE_CONTENTS);

			if (contents != null)
			{
				contents.items().forEach(this::addItem);
				return;
			}
		}

		Identifier id = BuiltInRegistries.ITEM.getKey(item.getItem());

		if (id != null)
		{
			this.result.addContainerItem(id, item.getCount());
		}
	}
}
