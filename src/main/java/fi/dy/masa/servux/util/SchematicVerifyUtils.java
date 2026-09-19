package fi.dy.masa.servux.util;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.Servux;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.verifier.VerifyMismatchType;
import fi.dy.masa.servux.schematic.verifier.VerifyNbtComparator;
import fi.dy.masa.servux.schematic.verifier.VerifyResult;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.servux.util.position.LayerRange;

/**
 * The read-only counterpart of {@link SchematicPlacingUtils}.
 * <p>
 * The traversal - which blocks a placement covers in a given chunk, and what the schematic
 * says should be there - lives in {@link SchematicRegionWalker}, so that a verification
 * walks exactly the same blocks a paste of the same placement would write. All that is left
 * here is the comparison itself.
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
		// Entities are not verified: Litematica's own verifier does not compare them either,
		// and there is no stable identity to pair a schematic entity with a world one by
		return SchematicRegionWalker.walkChunk(chunkPos, schematicPlacement, layerBehavior, layerRange,
		                                       (pos, expected, teNBT) ->
		                                       {
			                                       BlockState found = world.getBlockState(pos);
			                                       boolean stateMatches = classify(expected, found, pos, result);

			                                       // Contents are only meaningful once the block itself is right; a
			                                       // wrong block is already reported and would double count here
			                                       if (stateMatches && nbtComparator != null)
			                                       {
				                                       verifyBlockEntity(world, pos, expected, found, teNBT, result, nbtComparator);
			                                       }
		                                       },
		                                       null);
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
