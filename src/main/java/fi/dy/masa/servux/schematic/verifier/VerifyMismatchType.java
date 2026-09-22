package fi.dy.masa.servux.schematic.verifier;

import com.google.common.collect.ImmutableList;
import javax.annotation.Nullable;

import net.minecraft.ChatFormatting;

/**
 * Mismatch categories for the server side Schematic Verifier.
 * <p>
 * The ordinals are deliberately kept identical to Litematica's own
 * {@code SchematicVerifier.MismatchType}, so that the category id can be sent over the
 * wire as-is and mapped back to the client side enum without a translation table.
 * {@link #WRONG_NBT} is a Servux addition that does not exist in upstream Litematica;
 * clients that do not know about it are told via the {@code Features} metadata list.
 * {@link #MISSING_ENTITY} is not a block category at all: its entries are entities, which
 * travel in a list of their own rather than as expected/found pairs. It only lives here so
 * that it is counted, summarised and listed like the others.
 * <p>
 * Only the values in {@link #REPORTED} are ever produced by the verifier: {@link #ALL} and
 * {@link #CORRECT_STATE} are aggregate/GUI-only categories, and {@link #DIFF_BLOCK} is a
 * client side re-classification of {@link #WRONG_BLOCK}/{@link #WRONG_STATE} that requires
 * malilib's block grouping, which the server does not have.
 */
public enum VerifyMismatchType
{
	ALL          ("all",           ChatFormatting.WHITE),
	MISSING      ("missing",       ChatFormatting.AQUA),
	EXTRA        ("extra",         ChatFormatting.LIGHT_PURPLE),
	WRONG_BLOCK  ("wrong_block",   ChatFormatting.RED),
	WRONG_STATE  ("wrong_state",   ChatFormatting.GOLD),
	CORRECT_STATE("correct_state", ChatFormatting.GREEN),
	DIFF_BLOCK   ("diff_block",    ChatFormatting.YELLOW),
	WRONG_NBT    ("wrong_nbt",     ChatFormatting.DARK_AQUA),
	MISSING_ENTITY("missing_entity", ChatFormatting.DARK_PURPLE);

	/** The categories the server side verifier actually emits. */
	public static final ImmutableList<VerifyMismatchType> REPORTED =
			ImmutableList.of(MISSING, EXTRA, WRONG_BLOCK, WRONG_STATE, WRONG_NBT, MISSING_ENTITY);

	private static final VerifyMismatchType[] VALUES = values();

	private final String name;
	private final ChatFormatting color;

	VerifyMismatchType(String name, ChatFormatting color)
	{
		this.name = name;
		this.color = color;
	}

	public String getName()
	{
		return this.name;
	}

	public ChatFormatting getColor()
	{
		return this.color;
	}

	/** The wire id; identical to Litematica's {@code MismatchType} ordinal. */
	public int getId()
	{
		return this.ordinal();
	}

	public String getTranslationKey()
	{
		return "servux.litematics.verify.category." + this.name;
	}

	@Nullable
	public static VerifyMismatchType fromId(int id)
	{
		return id >= 0 && id < VALUES.length ? VALUES[id] : null;
	}

	@Nullable
	public static VerifyMismatchType fromName(String name)
	{
		for (VerifyMismatchType type : VALUES)
		{
			if (type.name.equalsIgnoreCase(name))
			{
				return type;
			}
		}

		return null;
	}
}
