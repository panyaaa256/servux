package fi.dy.masa.servux.util;

import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

import fi.dy.masa.servux.Servux;
import fi.dy.masa.servux.dataproviders.LitematicsDataProvider;
import fi.dy.masa.servux.schematic.LitematicaSchematic;
import fi.dy.masa.servux.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.placement.SubRegionPlacement;
import fi.dy.masa.servux.schematic.verifier.VerifyMismatchType;
import fi.dy.masa.servux.schematic.verifier.VerifyNbtComparator;
import fi.dy.masa.servux.schematic.verifier.VerifyResult;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.servux.util.IntBoundingBox;
import fi.dy.masa.servux.util.LayerRange;
import fi.dy.masa.servux.util.position.PositionUtils;

/**
 * The read-only counterpart of {@link SchematicPlacingUtils}.
 * <p>
 * The position/rotation/mirror arithmetic is a direct fork of
 * {@link SchematicPlacingUtils#placeBlocksWithinChunk} so that a verification walks exactly
 * the same blocks a paste of the same placement would write. Everything that mutates the
 * world (the {@code setBlock} calls, block entity loading and the scheduled tick copying)
 * is replaced by classification into a {@link VerifyResult}.
 * <p>
 * Because nothing here writes, {@link WorldUtils#setShouldPreventBlockUpdates} must
 * <i>not</i> be used: it is unnecessary for reads and would leak side effects onto other
 * server logic running in the same tick.
 */
public class SchematicVerifyUtils
{
	/**
	 * Verifies every sub-region of the given placement that touches {@code chunkPos}.
	 *
	 * @return false if any sub-region had missing/invalid schematic data
	 */
	public static boolean verifyWorldWithinChunk(ServerLevel world,
	                                             ChunkPos chunkPos,
	                                             SchematicPlacement schematicPlacement,
	                                             PasteLayerBehavior layerBehavior,
	                                             @Nullable LayerRange layerRange,
	                                             VerifyResult result,
	                                             @Nullable VerifyNbtComparator nbtComparator)
	{
		LitematicaSchematic schematic = schematicPlacement.getSchematic();
		Set<String> regionsTouchingChunk = schematicPlacement.getRegionsTouchingChunk(chunkPos.x, chunkPos.z);
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

			if (placement != null && placement.isEnabled())
			{
				Map<BlockPos, CompoundData> blockEntityMap = schematic.getBlockEntityMapForRegion(regionName);

				if (verifyBlocksWithinChunk(world, chunkPos, regionName, container, blockEntityMap, origin,
				                            schematicPlacement, placement, layerBehavior, layerRange,
				                            result, nbtComparator) == false)
				{
					allSuccess = false;
					Servux.LOGGER.warn("Invalid/missing schematic data in schematic '{}' for sub-region '{}'", schematic.getMetadata().getName(), regionName);
				}
			}
		}

		return allSuccess;
	}

	public static boolean verifyBlocksWithinChunk(ServerLevel world, ChunkPos chunkPos, String regionName,
	                                              LitematicaBlockStateContainer container,
	                                              @Nullable Map<BlockPos, CompoundData> blockEntityMap,
	                                              BlockPos origin,
	                                              SchematicPlacement schematicPlacement,
	                                              SubRegionPlacement placement,
	                                              PasteLayerBehavior layerBehavior,
	                                              @Nullable LayerRange layerRange,
	                                              VerifyResult result,
	                                              @Nullable VerifyNbtComparator nbtComparator)
	{
		IntBoundingBox bounds = schematicPlacement.getBoxWithinChunkForRegion(regionName, chunkPos.x, chunkPos.z);
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
			Servux.LOGGER.warn("verifyBlocksWithinChunk(): OUT OF BOUNDS - region: {}, sx: {}, sz: {}, ex: {}, ez: {} - size x: {} z: {}",
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
					// placement is verified against the contents a paste would actually write
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

					BlockState found = world.getBlockState(pos);

					boolean stateMatches = classify(expected, found, pos, result);

					// Contents are only meaningful once the block itself is right; a wrong
					// block is already reported and would double count here
					if (stateMatches && nbtComparator != null)
					{
						verifyBlockEntity(world, pos, expected, found, teNBT, result, nbtComparator);
					}
				}
			}
		}

		return true;
	}

	/**
	 * Classifies one expected/found pair, matching Litematica's {@code checkBlockStates()}.
	 * <p>
	 * The reference comparison on the states is intentional and mirrors the client side
	 * code: block states are canonical instances, so {@code !=} is an equality test here.
	 * <p>
	 * Note that the mismatch categories the client can additionally derive
	 * ({@code DIFF_BLOCK}) and the ignore mechanisms (ignored pairs, existing fluids, the
	 * ignore-block registry) are deliberately <i>not</i> applied here; they are display
	 * filters that the client applies to this raw classification.
	 */
	private static boolean classify(BlockState expected, BlockState found, BlockPos pos, VerifyResult result)
	{
		if (!expected.isAir()) { result.addSchematicBlock(); }
		if (!found.isAir())    { result.addWorldBlock(); }

		if (expected != found && (!expected.isAir() || !found.isAir()))
		{
			if (!expected.isAir())
			{
				VerifyMismatchType type = found.isAir() ? VerifyMismatchType.MISSING
				                        : expected.getBlock() != found.getBlock() ? VerifyMismatchType.WRONG_BLOCK
				                        : VerifyMismatchType.WRONG_STATE;

				result.add(type, expected, found, pos);
			}
			else
			{
				result.add(VerifyMismatchType.EXTRA, expected, found, pos);
			}

			return false;
		}

		result.addCorrectState(found, !expected.isAir());

		return true;
	}

	/**
	 * Compares container contents at a position whose block state already matches.
	 * <p>
	 * A {@code WRONG_NBT} entry therefore always carries two identical block states, and
	 * the position is <i>also</i> counted as a correct state - the block is right, only
	 * its contents are not. This category's count deliberately overlaps the others.
	 */
	private static void verifyBlockEntity(ServerLevel world, BlockPos pos,
	                                      BlockState expected, BlockState found,
	                                      @Nullable CompoundData expectedNbt,
	                                      VerifyResult result,
	                                      VerifyNbtComparator comparator)
	{
		if (!found.hasBlockEntity())
		{
			return;
		}

		BlockEntity be = world.getBlockEntity(pos);

		if (be == null)
		{
			return;
		}

		// The comparator works on vanilla tags so that it is identical across branches
		CompoundTag expectedTag = expectedNbt != null ? DataConverterNbt.toVanillaCompound(expectedNbt) : null;
		CompoundTag foundTag;

		try
		{
			foundTag = be.saveWithFullMetadata(world.registryAccess());
		}
		catch (Exception e)
		{
			Servux.LOGGER.warn("verifyBlockEntity(): failed to read the block entity at {}; {}", pos.toShortString(), e.getLocalizedMessage());
			return;
		}

		if (comparator.isComparable(expectedTag, foundTag) && comparator.differs(expectedTag, foundTag))
		{
			result.add(VerifyMismatchType.WRONG_NBT, expected, found, pos);
		}
	}
}
