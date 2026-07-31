package fi.dy.masa.servux.schematic.verifier;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Compares the contents of a schematic's block entity against the one in the world.
 * <p>
 * This is the part Litematica's client side verifier cannot do at all: it only ever
 * compares {@code BlockState}s, so a chest that is present but empty counts as correct.
 * <p>
 * Only containers are compared. Signs, spawners and the like carry data that is either
 * cosmetic or inherently unstable, and flagging it would drown the useful signal.
 * <p>
 * The default comparison is deliberately loose - a normalized multiset of
 * {@code (item id, count)} - because slot indices are not meaningful for the question
 * "does this container hold the right stuff". In particular a mirrored placement swaps the
 * halves of a double chest, which would make a slot-wise comparison report every single
 * double chest as wrong. Set {@code verify_nbt_slot_exact} when slot layout genuinely
 * matters, and {@code verify_nbt_strict} to also compare data components (enchantments,
 * custom names, and so on).
 * <p>
 * Everything here works on vanilla {@link CompoundTag}, so the class is identical across
 * branches that use different internal tag representations.
 */
public class VerifyNbtComparator
{
	private static final String ITEMS_KEY = "Items";
	private static final String SLOT_KEY = "Slot";
	private static final String ID_KEY = "id";
	private static final String COUNT_KEY = "count";
	private static final String COMPONENTS_KEY = "components";

	private final boolean slotExact;
	private final boolean strict;

	public VerifyNbtComparator(boolean slotExact, boolean strict)
	{
		this.slotExact = slotExact;
		this.strict = strict;
	}

	/**
	 * True when both sides describe a container whose contents can be meaningfully
	 * compared. A schematic that simply carries no block entity for a position is not a
	 * mismatch - it just says nothing about the contents.
	 */
	public boolean isComparable(@Nullable CompoundTag expected, @Nullable CompoundTag found)
	{
		if (expected == null || found == null)
		{
			return false;
		}

		return expected.contains(ITEMS_KEY) || found.contains(ITEMS_KEY);
	}

	/**
	 * @return true if the two block entities hold different contents
	 */
	public boolean differs(CompoundTag expected, CompoundTag found)
	{
		ListTag expectedItems = getItems(expected);
		ListTag foundItems = getItems(found);

		if (this.slotExact)
		{
			return !this.slotMapOf(expectedItems).equals(this.slotMapOf(foundItems));
		}

		return !this.multisetOf(expectedItems).equals(this.multisetOf(foundItems));
	}

	private static ListTag getItems(CompoundTag tag)
	{
		return tag.getListOrEmpty(ITEMS_KEY);
	}

	/** item key -> total count, ignoring which slot each stack sits in. */
	private Map<String, Integer> multisetOf(ListTag items)
	{
		Map<String, Integer> counts = new HashMap<>();

		for (int i = 0; i < items.size(); i++)
		{
			CompoundTag stack = items.getCompound(i).orElse(null);

			if (stack == null)
			{
				continue;
			}

			String key = this.keyOf(stack);
			int count = stack.getIntOr(COUNT_KEY, 1);

			if (count > 0)
			{
				counts.merge(key, count, Integer::sum);
			}
		}

		return counts;
	}

	/** slot -> "key xN", so both the layout and the stack sizes have to line up. */
	private Map<Integer, String> slotMapOf(ListTag items)
	{
		Map<Integer, String> slots = new HashMap<>();

		for (int i = 0; i < items.size(); i++)
		{
			CompoundTag stack = items.getCompound(i).orElse(null);

			if (stack == null)
			{
				continue;
			}

			int count = stack.getIntOr(COUNT_KEY, 1);

			if (count > 0)
			{
				slots.put(stack.getIntOr(SLOT_KEY, i), this.keyOf(stack) + " x" + count);
			}
		}

		return slots;
	}

	/**
	 * The identity a stack is compared by: its item id, plus its data components when
	 * strict mode asks for it.
	 */
	private String keyOf(CompoundTag stack)
	{
		String id = stack.getStringOr(ID_KEY, "");

		if (!this.strict)
		{
			return id;
		}

		Tag components = stack.get(COMPONENTS_KEY);

		return components != null ? id + Objects.toString(components) : id;
	}
}
