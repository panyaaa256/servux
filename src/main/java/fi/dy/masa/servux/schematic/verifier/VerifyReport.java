package fi.dy.masa.servux.schematic.verifier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.servux.util.StringUtils;

/**
 * Renders a {@link VerifyResult} as chat output.
 * <p>
 * Until the Litematica side gains a server-verify mode, chat is the only channel a
 * verification has, so the output is made navigable rather than merely readable: mismatch
 * positions carry a {@code /tp} suggestion, block states are spelled out with their
 * properties in a hover tooltip, and each non-empty category links to its own detail page.
 * <p>
 * These are plain vanilla system messages, so they render on unmodded clients too.
 */
public class VerifyReport
{
	public static final int DEFAULT_PAGE_SIZE = 8;
	/** How many positions of a given pair to offer teleports for. */
	private static final int MAX_POSITIONS_PER_PAIR = 5;

	/**
	 * The headline block: totals, chunk coverage and a line per non-empty category.
	 */
	public static List<Component> summary(VerifySession session)
	{
		VerifyResult result = session.getResult();
		List<Component> lines = new ArrayList<>();

		lines.add(StringUtils.translate("servux.litematics.verify.summary.header",
		                                session.getPlacementName(),
		                                session.getDimension(),
		                                session.getElapsedTime()));

		lines.add(StringUtils.translate("servux.litematics.verify.summary.blocks",
		                                result.getSchematicBlocks(),
		                                result.getWorldBlocks(),
		                                result.getCorrectStatesCount()));

		lines.add(StringUtils.translate("servux.litematics.verify.summary.chunks",
		                                result.getProcessedChunks(),
		                                result.getTotalChunks(),
		                                result.getUnloadedChunks(),
		                                result.getUngeneratedChunks()));

		if (result.getTotalMismatches() == 0)
		{
			lines.add(StringUtils.translate("servux.litematics.verify.summary.perfect").withStyle(ChatFormatting.GREEN));
		}
		else
		{
			lines.add(StringUtils.translate("servux.litematics.verify.summary.mismatches", result.getTotalMismatches()));

			for (VerifyMismatchType type : VerifyMismatchType.REPORTED)
			{
				int count = result.getCategoryCount(type);

				if (count == 0)
				{
					continue;
				}

				lines.add(categoryLine(type, count));
			}
		}

		if (result.isTruncated())
		{
			lines.add(StringUtils.translate("servux.litematics.verify.summary.truncated").withStyle(ChatFormatting.YELLOW));
		}

		if (result.getUnloadedChunks() > 0)
		{
			lines.add(StringUtils.translate("servux.litematics.verify.summary.unloaded_warning", result.getUnloadedChunks())
					          .withStyle(ChatFormatting.YELLOW));
		}

		if (result.getUngeneratedChunks() > 0)
		{
			lines.add(StringUtils.translate("servux.litematics.verify.summary.ungenerated_warning", result.getUngeneratedChunks())
					          .withStyle(ChatFormatting.YELLOW));
		}

		return lines;
	}

	private static Component categoryLine(VerifyMismatchType type, int count)
	{
		// Note: the i18n manager formats with String.format and wraps the result in a
		// literal, so arguments have to be plain strings - a Component would stringify.
		return StringUtils.translate("servux.litematics.verify.summary.category", categoryName(type), count)
				       .withStyle(type.getColor())
				       .withStyle(style -> style
						       .withClickEvent(new ClickEvent.RunCommand("/servux verify show " + type.getName()))
						       .withHoverEvent(new HoverEvent.ShowText(StringUtils.translate("servux.litematics.verify.hover.show_category"))));
	}

	private static String categoryName(VerifyMismatchType type)
	{
		return StringUtils.translateAsString(type.getTranslationKey());
	}

	/**
	 * One page of a category's expected/found pairs, each with teleport links to the first
	 * few positions it occurs at.
	 */
	public static List<Component> details(VerifySession session, VerifyMismatchType type, int page, int pageSize)
	{
		if (type == VerifyMismatchType.MISSING_ENTITY)
		{
			return entityDetails(session, page, pageSize);
		}

		List<Map.Entry<BlockMismatch, Collection<BlockPos>>> pairs = session.getResult().getMismatchesFor(type);
		List<Component> lines = new ArrayList<>();

		if (pairs.isEmpty())
		{
			lines.add(StringUtils.translate("servux.litematics.verify.details.empty", categoryName(type)));
			return lines;
		}

		final int pageCount = Math.max(1, (pairs.size() + pageSize - 1) / pageSize);
		final int clamped = Math.max(1, Math.min(page, pageCount));
		final int from = (clamped - 1) * pageSize;
		final int to = Math.min(from + pageSize, pairs.size());

		lines.add(StringUtils.translate("servux.litematics.verify.details.header",
		                                categoryName(type), clamped, pageCount, pairs.size())
				          .withStyle(type.getColor()));

		for (int i = from; i < to; i++)
		{
			Map.Entry<BlockMismatch, Collection<BlockPos>> entry = pairs.get(i);

			lines.add(pairLine(entry.getKey(), entry.getValue()));
		}

		addNextPageLine(lines, type, clamped, pageCount);

		return lines;
	}

	/**
	 * One page of the missing entities, a line per entity type with teleport links to the
	 * first few places one is missing from - the entity counterpart of the block pair list.
	 */
	private static List<Component> entityDetails(VerifySession session, int page, int pageSize)
	{
		VerifyResult result = session.getResult();
		VerifyMismatchType type = VerifyMismatchType.MISSING_ENTITY;
		List<Component> lines = new ArrayList<>();

		if (result.isEntitiesChecked() == false)
		{
			lines.add(StringUtils.translate("servux.litematics.verify.details.entities_not_checked"));
			return lines;
		}

		Map<String, List<Vec3>> byType = new LinkedHashMap<>();

		for (VerifyResult.MissingEntity entity : result.getMissingEntities())
		{
			byType.computeIfAbsent(entity.entityId(), k -> new ArrayList<>()).add(entity.pos());
		}

		if (byType.isEmpty())
		{
			lines.add(StringUtils.translate("servux.litematics.verify.details.empty", categoryName(type)));
			return lines;
		}

		List<Map.Entry<String, List<Vec3>>> types = new ArrayList<>(byType.entrySet());
		types.sort((a, b) -> Integer.compare(b.getValue().size(), a.getValue().size()));

		final int pageCount = Math.max(1, (types.size() + pageSize - 1) / pageSize);
		final int clamped = Math.max(1, Math.min(page, pageCount));
		final int from = (clamped - 1) * pageSize;
		final int to = Math.min(from + pageSize, types.size());

		lines.add(StringUtils.translate("servux.litematics.verify.details.entity_header",
		                                categoryName(type), clamped, pageCount, types.size())
				          .withStyle(type.getColor()));

		for (int i = from; i < to; i++)
		{
			lines.add(entityLine(types.get(i).getKey(), types.get(i).getValue()));
		}

		addNextPageLine(lines, type, clamped, pageCount);

		return lines;
	}

	/** A clickable "next page" line, if there is a next page. */
	private static void addNextPageLine(List<Component> lines, VerifyMismatchType type, int page, int pageCount)
	{
		if (page < pageCount)
		{
			lines.add(StringUtils.translate("servux.litematics.verify.details.next_page", page + 1)
					          .withStyle(style -> style
							          .withClickEvent(new ClickEvent.RunCommand("/servux verify show " + type.getName() + " " + (page + 1)))
							          .withColor(ChatFormatting.GRAY)));
		}
	}

	private static Component entityLine(String entityId, List<Vec3> positions)
	{
		MutableComponent text = Component.empty();
		String shortName = entityId.startsWith("minecraft:") ? entityId.substring("minecraft:".length()) : entityId;

		text.append(Component.literal(shortName).withStyle(style -> style
				.withColor(ChatFormatting.AQUA)
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(entityId)))));
		text.append(Component.literal(" x" + positions.size()).withStyle(ChatFormatting.GRAY));

		for (int i = 0; i < positions.size() && i < MAX_POSITIONS_PER_PAIR; i++)
		{
			text.append(" ").append(posComponent(BlockPos.containing(positions.get(i))));
		}

		return text;
	}

	private static Component pairLine(BlockMismatch mismatch, Collection<BlockPos> positions)
	{
		MutableComponent text = Component.empty();

		text.append(stateComponent(mismatch.expected()).withStyle(ChatFormatting.AQUA));
		text.append(Component.literal(" -> ").withStyle(ChatFormatting.GRAY));
		text.append(stateComponent(mismatch.found()).withStyle(ChatFormatting.RED));
		text.append(Component.literal(" x" + positions.size()).withStyle(ChatFormatting.GRAY));

		int shown = 0;

		for (BlockPos pos : positions)
		{
			if (shown >= MAX_POSITIONS_PER_PAIR)
			{
				break;
			}

			text.append(" ").append(posComponent(pos));
			shown++;
		}

		return text;
	}

	/** A clickable position that suggests a teleport to it. */
	private static Component posComponent(BlockPos pos)
	{
		String coords = pos.getX() + " " + pos.getY() + " " + pos.getZ();

		return Component.literal("[" + coords + "]").withStyle(style -> style
				.withColor(ChatFormatting.DARK_AQUA)
				.withClickEvent(new ClickEvent.SuggestCommand("/tp @s " + coords))
				.withHoverEvent(new HoverEvent.ShowText(StringUtils.translate("servux.litematics.verify.hover.teleport", coords))));
	}

	/** The block's registry name, with the full state spelled out in the tooltip. */
	private static MutableComponent stateComponent(BlockState state)
	{
		String full = stateToString(state);
		String shortName = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();

		return Component.literal(shortName).withStyle(style ->
				                                              style.withHoverEvent(new HoverEvent.ShowText(Component.literal(full))));
	}

	/** {@code namespace:path[prop=value,...]}, the same shape the block state parser accepts. */
	public static String stateToString(BlockState state)
	{
		StringBuilder builder = new StringBuilder(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());

		if (!state.isSingletonState())
		{
			StringJoiner joiner = new StringJoiner(",", "[", "]");

			state.getValues().forEach(value -> joiner.add(value.property().getName() + "=" + value.valueName()));

			builder.append(joiner);
		}

		return builder.toString();
	}
}
