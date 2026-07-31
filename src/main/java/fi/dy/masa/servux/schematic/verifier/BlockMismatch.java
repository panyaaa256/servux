package fi.dy.masa.servux.schematic.verifier;

import net.minecraft.world.level.block.state.BlockState;

/**
 * One expected/found {@link BlockState} pair, plus the category it was classified as.
 * <p>
 * {@code BlockState} instances are canonical (interned in the global palette), so the
 * record's generated {@code equals}/{@code hashCode} end up being identity comparisons on
 * the states, which is exactly what Litematica's own {@code BlockMismatch} relies on.
 */
public record BlockMismatch(VerifyMismatchType type, BlockState expected, BlockState found)
{
}
