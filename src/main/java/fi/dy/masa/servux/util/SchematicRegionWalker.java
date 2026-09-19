package fi.dy.masa.servux.util;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.servux.Servux;
import fi.dy.masa.servux.dataproviders.LitematicsDataProvider;
import fi.dy.masa.servux.schematic.LitematicaSchematic;
import fi.dy.masa.servux.schematic.LitematicaSchematic.EntityInfo;
import fi.dy.masa.servux.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.placement.SubRegionPlacement;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.position.IntBoundingBox;
import fi.dy.masa.servux.util.position.LayerRange;
import fi.dy.masa.servux.util.position.PositionUtils;

/**
 * Walks the part of a placement that falls inside one chunk, handing every block (and
 * optionally every entity) it would paste there to a visitor.
 * <p>
 * The position/rotation/mirror arithmetic is a direct fork of
 * {@link SchematicPlacingUtils#placeBlocksWithinChunk} and
 * {@link SchematicPlacingUtils#placeEntitiesToWorldWithinChunk}, so that anything built on
 * this visits exactly the blocks a paste of the same placement would write. Everything that
 * mutates the world is gone; what is left is the traversal, and the traversal alone.
 * <p>
 * Two read-only walks are built on it - {@link SchematicVerifyUtils}, which classifies each
 * block against the world, and {@link SchematicMaterialListUtils}, which tallies what the
 * placement would cost. They differ only in what they do per position, so keeping the
 * arithmetic in one place is the difference between one fork of it and three.
 * <p>
 * Note that nothing here touches the world: the expected state and its block entity data
 * come out of the schematic, and what to compare them against is the visitor's business.
 * That is also why {@link WorldUtils#setShouldPreventBlockUpdates} must <i>not</i> be used
 * around a walk - it is unnecessary for reads and would leak side effects onto other server
 * logic running in the same tick.
 */
public class SchematicRegionWalker
{
	/**
	 * Receives one position the placement covers.
	 *
	 * @param worldPos         where the block would end up in the world
	 * @param expected         the schematic's state, already mirrored and rotated
	 * @param blockEntityNbt   the schematic's block entity data for it, or null
	 */
	@FunctionalInterface
	public interface BlockVisitor
	{
		void visit(BlockPos worldPos, BlockState expected, @Nullable CompoundData blockEntityNbt);
	}

	/**
	 * Receives one entity the placement would spawn in this chunk.
	 *
	 * @param worldPos where the entity would end up in the world
	 * @param nbt      the schematic's entity data, <i>not</i> copied - visitors must only read it
	 */
	@FunctionalInterface
	public interface EntityVisitor
	{
		void visit(Vec3 worldPos, CompoundData nbt);
	}

	/**
	 * Walks every sub-region of the given placement that touches {@code chunkPos}.
	 *
	 * @param entityVisitor null to skip the entity walk entirely, which also skips reading
	 *                      the schematic's entity lists
	 * @return false if any sub-region had missing/invalid schematic data
	 */
	public static boolean walkChunk(ChunkPos chunkPos,
	                                SchematicPlacement schematicPlacement,
	                                PasteLayerBehavior layerBehavior,
	                                @Nullable LayerRange layerRange,
	                                BlockVisitor blockVisitor,
	                                @Nullable EntityVisitor entityVisitor)
	{
		LitematicaSchematic schematic = schematicPlacement.getSchematic();
		Set<String> regionsTouchingChunk = schematicPlacement.getRegionsTouchingChunk(chunkPos.x(), chunkPos.z());
		BlockPos origin = schematicPlacement.getOrigin();
		boolean allSuccess = true;

		for (String regionName : regionsTouchingChunk)
		{
			LitematicaBlockStateContainer container = schematic.getSubRegionContainer(regionName);

			if (container == null)
			{
				allSuccess = false;
				continue;
			}

			SubRegionPlacement placement = schematicPlacement.getRelativeSubRegionPlacement(regionName);

			if (placement == null || !placement.isEnabled())
			{
				continue;
			}

			Map<BlockPos, CompoundData> blockEntityMap = schematic.getBlockEntityMapForRegion(regionName);

			if (walkBlocksWithinChunk(chunkPos, regionName, container, blockEntityMap, origin,
			                          schematicPlacement, placement, layerBehavior, layerRange,
			                          blockVisitor) == false)
			{
				allSuccess = false;
				Servux.LOGGER.warn("Invalid/missing schematic data in schematic '{}' for sub-region '{}'", schematic.getMetadata().getName(), regionName);
			}

			// The same gates a paste applies, so that a walk never reports entities a paste
			// of the same placement would not have spawned
			if (entityVisitor != null && !schematicPlacement.ignoreEntities() && !placement.ignoreEntities())
			{
				List<EntityInfo> entityList = schematic.getEntityListForRegion(regionName);

				if (entityList != null)
				{
					walkEntitiesWithinChunk(chunkPos, entityList, origin, schematicPlacement, placement,
					                        layerBehavior, layerRange, entityVisitor);
				}
			}
		}

		return allSuccess;
	}

	/** @return false if the sub-region's data does not line up with its declared size */
	private static boolean walkBlocksWithinChunk(ChunkPos chunkPos, String regionName,
	                                             LitematicaBlockStateContainer container,
	                                             @Nullable Map<BlockPos, CompoundData> blockEntityMap,
	                                             BlockPos origin,
	                                             SchematicPlacement schematicPlacement,
	                                             SubRegionPlacement placement,
	                                             PasteLayerBehavior layerBehavior,
	                                             @Nullable LayerRange layerRange,
	                                             BlockVisitor visitor)
	{
		IntBoundingBox bounds = schematicPlacement.getBoxWithinChunkForRegion(regionName, chunkPos.x(), chunkPos.z());
		Vec3i regionSize = schematicPlacement.getSchematic().getAreaSize(regionName);

		if (bounds == null || regionSize == null)
		{
			return false;
		}

		BlockPos regionPos = placement.getPos();

		// These are the untransformed relative positions
		BlockPos posEndRel = (new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(regionSize))).offset(regionPos);
		BlockPos posMinRel = PositionUtils.getMinCorner(regionPos, posEndRel);

		// The transformed sub-region origin position
		BlockPos regionPosTransformed = PositionUtils.getTransformedBlockPos(regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());

		// The relative offset of the affected region's corners, to the sub-region's origin corner
		BlockPos boxMinRel = new BlockPos(bounds.minX() - origin.getX() - regionPosTransformed.getX(), 0, bounds.minZ() - origin.getZ() - regionPosTransformed.getZ());
		BlockPos boxMaxRel = new BlockPos(bounds.maxX() - origin.getX() - regionPosTransformed.getX(), 0, bounds.maxZ() - origin.getZ() - regionPosTransformed.getZ());

		// Reverse transform that relative offset, to get the untransformed orientation's offsets
		boxMinRel = PositionUtils.getReverseTransformedBlockPos(boxMinRel, placement.getMirror(), placement.getRotation());
		boxMaxRel = PositionUtils.getReverseTransformedBlockPos(boxMaxRel, placement.getMirror(), placement.getRotation());

		boxMinRel = PositionUtils.getReverseTransformedBlockPos(boxMinRel, schematicPlacement.getMirror(), schematicPlacement.getRotation());
		boxMaxRel = PositionUtils.getReverseTransformedBlockPos(boxMaxRel, schematicPlacement.getMirror(), schematicPlacement.getRotation());

		// Get the offset relative to the sub-region's minimum corner, instead of the origin corner (which can be at any corner)
		boxMinRel = boxMinRel.subtract(posMinRel.subtract(regionPos));
		boxMaxRel = boxMaxRel.subtract(posMinRel.subtract(regionPos));

		BlockPos posMin = PositionUtils.getMinCorner(boxMinRel, boxMaxRel);
		BlockPos posMax = PositionUtils.getMaxCorner(boxMinRel, boxMaxRel);

		final int startX = posMin.getX();
		final int startZ = posMin.getZ();
		final int endX = posMax.getX();
		final int endZ = posMax.getZ();

		final int startY = 0;
		final int endY = Math.abs(regionSize.getY()) - 1;
		BlockPos.MutableBlockPos posMutable = new BlockPos.MutableBlockPos();

		if (startX < 0 || startZ < 0 || endX >= container.getSize().getX() || endZ >= container.getSize().getZ())
		{
			Servux.LOGGER.warn("walkBlocksWithinChunk(): OUT OF BOUNDS - region: {}, sx: {}, sz: {}, ex: {}, ez: {} - size x: {} z: {}",
			                   regionName, startX, startZ, endX, endZ, container.getSize().getX(), container.getSize().getZ());
			return false;
		}

		final Rotation rotationCombined = schematicPlacement.getRotation().getRotated(placement.getRotation());
		final Mirror mirrorMain = schematicPlacement.getMirror();
		Mirror mirrorSub = placement.getMirror();

		if (mirrorSub != Mirror.NONE &&
			(schematicPlacement.getRotation() == Rotation.CLOCKWISE_90 ||
			schematicPlacement.getRotation() == Rotation.COUNTERCLOCKWISE_90))
		{
			mirrorSub = mirrorSub == Mirror.FRONT_BACK ? Mirror.LEFT_RIGHT : Mirror.FRONT_BACK;
		}

		final int posMinRelMinusRegX = posMinRel.getX() - regionPos.getX();
		final int posMinRelMinusRegY = posMinRel.getY() - regionPos.getY();
		final int posMinRelMinusRegZ = posMinRel.getZ() - regionPos.getZ();

		for (int y = startY; y <= endY; ++y)
		{
			for (int z = startZ; z <= endZ; ++z)
			{
				for (int x = startX; x <= endX; ++x)
				{
					BlockState expected = container.get(x, y, z);

					if (expected.getBlock() == Blocks.STRUCTURE_VOID)
					{
						continue;
					}

					posMutable.set(x, y, z);
					CompoundData teNBT = blockEntityMap != null ? blockEntityMap.get(posMutable) : null;
					BlockPos origPos = posMutable.immutable();

					posMutable.set(posMinRelMinusRegX + x,
					               posMinRelMinusRegY + y,
					               posMinRelMinusRegZ + z);

					BlockPos pos = PositionUtils.getTransformedPlacementPosition(posMutable, schematicPlacement, placement);
					pos = pos.offset(regionPosTransformed).offset(origin);

					if (!SchematicPlacingUtils.shouldPasteBlock(pos, layerBehavior, layerRange))
					{
						continue;
					}

					// Same double chest correction the paste path applies, so that a mirrored
					// placement is walked against the contents a paste would actually write
					if (blockEntityMap != null && expected.hasBlockEntity() && expected.is(Blocks.CHEST) &&
						mirrorMain != Mirror.NONE &&
						!(expected.getValue(ChestBlock.TYPE) == ChestType.SINGLE) &&
						LitematicsDataProvider.INSTANCE.isEnabled() &&
						LitematicsDataProvider.INSTANCE.fixChestMirror.getValue())
					{
						Direction facing = expected.getValue(ChestBlock.FACING);
						Direction.Axis axis = facing.getAxis();
						ChestType type = expected.getValue(ChestBlock.TYPE).getOpposite();

						if (axis != Direction.Axis.Y)
						{
							Direction facingAdj = type == ChestType.LEFT ? facing.getCounterClockWise(Direction.Axis.Y) : facing.getClockWise(Direction.Axis.Y);
							BlockPos posAdj = origPos.relative(facingAdj);
							teNBT = blockEntityMap.getOrDefault(posAdj, teNBT);
						}
					}

					if (mirrorMain != Mirror.NONE) { expected = expected.mirror(mirrorMain); }
					if (mirrorSub != Mirror.NONE)  { expected = expected.mirror(mirrorSub); }
					if (rotationCombined != Rotation.NONE) { expected = expected.rotate(rotationCombined); }

					visitor.visit(pos, expected, teNBT);
				}
			}
		}

		return true;
	}

	/**
	 * Hands over the entities of one sub-region that land inside this chunk.
	 * <p>
	 * The chunk test is on the transformed position, exactly as in the paste path, so an
	 * entity is reported by the chunk it would actually spawn in rather than the one its
	 * sub-region happens to start in.
	 */
	private static void walkEntitiesWithinChunk(ChunkPos chunkPos,
	                                            List<EntityInfo> entityList,
	                                            BlockPos origin,
	                                            SchematicPlacement schematicPlacement,
	                                            SubRegionPlacement placement,
	                                            PasteLayerBehavior layerBehavior,
	                                            @Nullable LayerRange layerRange,
	                                            EntityVisitor visitor)
	{
		BlockPos regionPos = placement.getPos();
		BlockPos regionPosRelTransformed = PositionUtils.getTransformedBlockPos(regionPos, schematicPlacement.getMirror(), schematicPlacement.getRotation());

		final int offX = regionPosRelTransformed.getX() + origin.getX();
		final int offY = regionPosRelTransformed.getY() + origin.getY();
		final int offZ = regionPosRelTransformed.getZ() + origin.getZ();
		final double minX = (chunkPos.x() << 4);
		final double minZ = (chunkPos.z() << 4);
		final double maxX = (chunkPos.x() << 4) + 16;
		final double maxZ = (chunkPos.z() << 4) + 16;

		for (EntityInfo info : entityList)
		{
			Vec3 pos = info.posVec();
			pos = PositionUtils.getTransformedPosition(pos, schematicPlacement.getMirror(), schematicPlacement.getRotation());
			pos = PositionUtils.getTransformedPosition(pos, placement.getMirror(), placement.getRotation());

			double x = pos.x + offX;
			double y = pos.y + offY;
			double z = pos.z + offZ;

			if (!SchematicPlacingUtils.shouldPasteEntity(new Vec3(x, y, z), layerBehavior, layerRange))
			{
				continue;
			}

			if (x >= minX && x < maxX && z >= minZ && z < maxZ)
			{
				visitor.visit(new Vec3(x, y, z), info.nbt());
			}
		}
	}
}
