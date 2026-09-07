package fi.dy.masa.servux.commands;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.Reference;
import fi.dy.masa.servux.dataproviders.LitematicsDataProvider;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.scheduler.session.ServerTaskSession;
import fi.dy.masa.servux.scheduler.session.ServerTaskSessionManager;
import fi.dy.masa.servux.schematic.analyzer.AnalyzeResult;
import fi.dy.masa.servux.schematic.analyzer.AnalyzeSession;
import fi.dy.masa.servux.schematic.selection.AreaSelection;
import fi.dy.masa.servux.schematic.selection.Box;
import fi.dy.masa.servux.util.PermissionsUtil;
import fi.dy.masa.servux.util.StringUtils;

/**
 * The {@code /servux analyze} sub-tree.
 * <p>
 * The area is given as two explicit corners rather than borrowed from a selection, because
 * a command source has no client side selection to borrow. The packet route is what a
 * player's actual area selection goes through; this exists so the feature can be exercised
 * and debugged without a client at all.
 */
public class ServuxAnalyzeCommand
{
	/** How many block/item lines one page of the report holds. */
	public static final int DEFAULT_PAGE_SIZE = 20;

	public static ArgumentBuilder<CommandSourceStack, ?> build()
	{
		return Commands.literal("analyze")
		               .requires(PermissionsUtil.require(Reference.MOD_ID + ".commands.analyze", 4))
		               .then(Commands.literal("start")
		                             .then(Commands.argument("from", BlockPosArgument.blockPos())
		                                           .then(Commands.argument("to", BlockPosArgument.blockPos())
		                                                         .executes(ctx -> start(ctx, false, true))
		                                                         .then(Commands.argument("entities", BoolArgumentType.bool())
		                                                                       .executes(ctx -> start(ctx, BoolArgumentType.getBool(ctx, "entities"), true))
		                                                                       .then(Commands.argument("containers", BoolArgumentType.bool())
		                                                                                     .executes(ctx -> start(ctx,
		                                                                                                            BoolArgumentType.getBool(ctx, "entities"),
		                                                                                                            BoolArgumentType.getBool(ctx, "containers"))))))))
		               .then(Commands.literal("status").executes(ServuxAnalyzeCommand::status))
		               .then(Commands.literal("cancel")
		                             .executes(ctx -> cancel(ctx, null))
		                             .then(Commands.argument("session", StringArgumentType.string())
		                                           .executes(ctx -> cancel(ctx, StringArgumentType.getString(ctx, "session")))))
		               .then(Commands.literal("show")
		                             .executes(ctx -> show(ctx, 1))
		                             .then(Commands.argument("page", IntegerArgumentType.integer(1))
		                                           .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "page")))));
	}

	/** The requester identity used to enforce one running session each. */
	private static UUID ownerOf(CommandSourceStack source)
	{
		ServerPlayer player = source.getPlayer();

		return player != null ? player.getUUID() : ServerTaskSession.CONSOLE_OWNER;
	}

	private static int start(CommandContext<CommandSourceStack> ctx, boolean entities, boolean containers) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		ServerLevel level = source.getLevel();

		BlockPos from = BlockPosArgument.getBlockPos(ctx, "from");
		BlockPos to = BlockPosArgument.getBlockPos(ctx, "to");

		AreaSelection area = new AreaSelection();
		area.setName(StringUtils.translate("servux.litematics.analyze.command_area_name").getString());
		area.addSubRegionBox(new Box(from, to, "command"), true);

		if (LitematicsDataProvider.INSTANCE.exceedsAnalyzeVolume(area))
		{
			throw StringUtils.translateError("servux.litematics.analyze.error.too_large");
		}

		AnalyzeSession session = LitematicsDataProvider.INSTANCE.startAnalyze(
				level, area, null, entities, containers, ownerOf(source), source, null,
				done -> summary(source, done));

		if (session == null)
		{
			throw StringUtils.translateError("servux.litematics.analyze.error.already_running");
		}

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.started",
		                                               level.dimension().identifier().toString(),
		                                               session.getProgress().getTotalChunks()), false);

		return 1;
	}

	private static void summary(CommandSourceStack source, AnalyzeSession session)
	{
		AnalyzeResult result = session.getResult();

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.finished",
		                                               result.getBlocksCounted(),
		                                               result.getDistinctBlockStates(),
		                                               session.getElapsedTime()), false);

		if (result.getProgress().getSkippedChunks() > 0)
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.skipped_chunks",
			                                               result.getProgress().getUnloadedChunks(),
			                                               result.getProgress().getUngeneratedChunks())
			                                    .withStyle(ChatFormatting.YELLOW), false);
		}

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.show_hint")
		                                    .withStyle(style -> style
				                                    .withClickEvent(new ClickEvent.RunCommand("/servux analyze show 1"))
				                                    .withHoverEvent(new HoverEvent.ShowText(
						                                    StringUtils.translate("servux.litematics.analyze.hover.show")))), false);
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		List<ServerTaskSession> running = ServerTaskSessionManager.INSTANCE.getAllRunning(ServerTaskKind.ANALYZE);

		if (running.isEmpty())
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.status.none"), false);
			return 0;
		}

		for (ServerTaskSession session : running)
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.status.entry",
			                                               session.getSubject(),
			                                               session.getProgress().getProcessedChunks(),
			                                               session.getProgress().getTotalChunks(),
			                                               session.getElapsedTime())
			                                    .withStyle(style -> style
					                                    .withClickEvent(new ClickEvent.SuggestCommand("/servux analyze cancel " + session.getSessionId()))
					                                    .withHoverEvent(new HoverEvent.ShowText(
							                                    StringUtils.translate("servux.litematics.analyze.hover.cancel")))),
			                   false);
		}

		return running.size();
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx, @Nullable String sessionId) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		AnalyzeSession session;

		if (sessionId != null)
		{
			try
			{
				session = ServerTaskSessionManager.INSTANCE.get(UUID.fromString(sessionId), AnalyzeSession.class);
			}
			catch (IllegalArgumentException e)
			{
				throw StringUtils.translateError("servux.litematics.analyze.error.bad_session", sessionId);
			}
		}
		else
		{
			session = (AnalyzeSession) ServerTaskSessionManager.INSTANCE.getRunningFor(ownerOf(source), ServerTaskKind.ANALYZE);
		}

		if (session == null || !session.isRunning())
		{
			throw StringUtils.translateError("servux.litematics.analyze.error.no_session");
		}

		final AnalyzeSession cancelled = session;

		cancelled.cancel();

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.cancelled", cancelled.getAreaName()), true);

		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		AnalyzeSession session = (AnalyzeSession) ServerTaskSessionManager.INSTANCE.getLatestFor(ownerOf(source), ServerTaskKind.ANALYZE);

		if (session == null)
		{
			throw StringUtils.translateError("servux.litematics.analyze.error.no_result");
		}

		if (session.isRunning())
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.analyze.show.still_running").withStyle(ChatFormatting.YELLOW), false);
		}

		List<Component> lines = report(session.getResult(), page, DEFAULT_PAGE_SIZE);

		for (Component line : lines)
		{
			source.sendSuccess(() -> line, false);
		}

		return lines.size();
	}

	/** One page of the tallies, biggest count first. */
	private static List<Component> report(AnalyzeResult result, int page, int pageSize)
	{
		List<Component> lines = new ArrayList<>();

		List<Map.Entry<BlockState, Integer>> blocks = sorted(result.getBlockCounts());
		final int totalPages = Math.max(1, (blocks.size() + pageSize - 1) / pageSize);
		final int clamped = Math.min(page, totalPages);
		final int start = (clamped - 1) * pageSize;
		final int end = Math.min(start + pageSize, blocks.size());

		lines.add(StringUtils.translate("servux.litematics.analyze.report.header", clamped, totalPages, blocks.size()));

		for (int i = start; i < end; i++)
		{
			Map.Entry<BlockState, Integer> entry = blocks.get(i);

			lines.add(StringUtils.translate("servux.litematics.analyze.report.block",
			                                entry.getKey().getBlock().getName(),
			                                entry.getValue()));
		}

		// The two extra tallies are short enough to print whole, and only on the last page
		if (clamped == totalPages)
		{
			appendIdCounts(lines, result.getEntityCounts(), "servux.litematics.analyze.report.entity");
			appendIdCounts(lines, result.getContainerItemCounts(), "servux.litematics.analyze.report.item");
		}

		return lines;
	}

	private static void appendIdCounts(List<Component> lines, Object2IntOpenHashMap<Identifier> counts, String key)
	{
		List<Map.Entry<Identifier, Integer>> entries = sorted(counts);

		for (Map.Entry<Identifier, Integer> entry : entries)
		{
			lines.add(StringUtils.translate(key, entry.getKey().toString(), entry.getValue()));
		}
	}

	private static <T> List<Map.Entry<T, Integer>> sorted(Object2IntOpenHashMap<T> counts)
	{
		List<Map.Entry<T, Integer>> list = new ArrayList<>(counts.object2IntEntrySet().stream()
		                                                         .map(e -> Map.entry(e.getKey(), e.getIntValue()))
		                                                         .toList());

		list.sort(Comparator.<Map.Entry<T, Integer>>comparingInt(Map.Entry::getValue).reversed());

		return list;
	}
}
