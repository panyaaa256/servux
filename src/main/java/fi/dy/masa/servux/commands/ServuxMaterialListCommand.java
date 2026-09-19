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
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import fi.dy.masa.servux.Reference;
import fi.dy.masa.servux.dataproviders.LitematicsDataProvider;
import fi.dy.masa.servux.interop.SyncmaticaBridge;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.scheduler.session.ServerTaskSession;
import fi.dy.masa.servux.scheduler.session.ServerTaskSessionManager;
import fi.dy.masa.servux.schematic.materials.MaterialListResult;
import fi.dy.masa.servux.schematic.materials.MaterialListSession;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.util.PermissionsUtil;
import fi.dy.masa.servux.util.StringUtils;

/**
 * The {@code /servux materials} sub-tree.
 * <p>
 * The placement comes from Syncmatica, the same way {@link ServuxVerifyCommand} gets one: a
 * material list needs a schematic to price up, and a command source has no client side
 * placement to borrow. The packet route is what a player's own placement goes through; this
 * exists so the feature can be exercised and debugged without a client at all.
 */
public class ServuxMaterialListCommand
{
	/** How many block/item lines one page of the report holds. */
	public static final int DEFAULT_PAGE_SIZE = 20;

	public static ArgumentBuilder<CommandSourceStack, ?> build()
	{
		return Commands.literal("materials")
		               .requires(PermissionsUtil.require(Reference.MOD_ID + ".commands.materials", 4))
		               .then(Commands.literal("start")
		                             .then(Commands.argument("placement", StringArgumentType.string())
		                                           .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
				                                           placementNames(ctx.getSource().getServer()), builder))
		                                           .executes(ctx -> start(ctx, false))
		                                           .then(Commands.argument("ignore_state", BoolArgumentType.bool())
		                                                         .executes(ctx -> start(ctx, BoolArgumentType.getBool(ctx, "ignore_state"))))))
		               .then(Commands.literal("status").executes(ServuxMaterialListCommand::status))
		               .then(Commands.literal("cancel")
		                             .executes(ctx -> cancel(ctx, null))
		                             .then(Commands.argument("session", StringArgumentType.string())
		                                           .executes(ctx -> cancel(ctx, StringArgumentType.getString(ctx, "session")))))
		               .then(Commands.literal("show")
		                             .executes(ctx -> show(ctx, 1))
		                             .then(Commands.argument("page", IntegerArgumentType.integer(1))
		                                           .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "page")))));
	}

	private static List<String> placementNames(MinecraftServer server)
	{
		if (!LitematicsDataProvider.INSTANCE.isSyncmaticaInteropEnabled())
		{
			return List.of();
		}

		return SyncmaticaBridge.INSTANCE.getPlacements(server).stream()
		                                .map(SyncmaticaBridge.SyncmaticaPlacement::displayName)
		                                .toList();
	}

	/** The requester identity used to enforce one running session each. */
	private static UUID ownerOf(CommandSourceStack source)
	{
		ServerPlayer player = source.getPlayer();

		return player != null ? player.getUUID() : ServerTaskSession.CONSOLE_OWNER;
	}

	private static int start(CommandContext<CommandSourceStack> ctx, boolean ignoreState) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		String name = StringArgumentType.getString(ctx, "placement");

		if (!LitematicsDataProvider.INSTANCE.isSyncmaticaInteropEnabled())
		{
			throw StringUtils.translateError("servux.litematics.verify.error.interop_disabled");
		}

		MinecraftServer server = source.getServer();
		SyncmaticaBridge.SyncmaticaPlacement info = SyncmaticaBridge.INSTANCE.findPlacement(server, name);

		if (info == null)
		{
			throw StringUtils.translateError("servux.litematics.verify.error.unknown_placement", name);
		}

		ServerLevel level = SyncmaticaBridge.INSTANCE.getLevel(server, info);

		if (level == null)
		{
			throw StringUtils.translateError("servux.litematics.verify.error.unknown_dimension",
			                                 info.dimension().identifier().toString());
		}

		SchematicPlacement placement = SyncmaticaBridge.INSTANCE.loadPlacement(info);

		if (placement == null)
		{
			throw StringUtils.translateError("servux.litematics.verify.error.load_failed", info.displayName());
		}

		MaterialListSession session = LitematicsDataProvider.INSTANCE.startMaterialList(
				level, placement, null, ignoreState, true, true, ownerOf(source), source, null,
				done -> summary(source, done));

		if (session == null)
		{
			throw StringUtils.translateError("servux.litematics.materials.error.already_running");
		}

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.started",
		                                               placement.getName(),
		                                               level.dimension().identifier().toString(),
		                                               session.getProgress().getTotalChunks()), false);

		return 1;
	}

	private static void summary(CommandSourceStack source, MaterialListSession session)
	{
		MaterialListResult result = session.getResult();

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.finished",
		                                               result.getBlocksMissing(),
		                                               result.getBlocksTotal(),
		                                               result.getBlocksMismatched(),
		                                               session.getElapsedTime()), false);

		if (result.getProgress().getSkippedChunks() > 0)
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.skipped_chunks",
			                                               result.getProgress().getUnloadedChunks(),
			                                               result.getProgress().getUngeneratedChunks())
			                                    .withStyle(ChatFormatting.YELLOW), false);
		}

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.show_hint")
		                                    .withStyle(style -> style
				                                    .withClickEvent(new ClickEvent.RunCommand("/servux materials show 1"))
				                                    .withHoverEvent(new HoverEvent.ShowText(
						                                    StringUtils.translate("servux.litematics.materials.hover.show")))), false);
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		List<ServerTaskSession> running = ServerTaskSessionManager.INSTANCE.getAllRunning(ServerTaskKind.MATERIALS);

		if (running.isEmpty())
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.status.none"), false);
			return 0;
		}

		for (ServerTaskSession session : running)
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.status.entry",
			                                               session.getSubject(),
			                                               session.getProgress().getProcessedChunks(),
			                                               session.getProgress().getTotalChunks(),
			                                               ((MaterialListSession) session).getResult().getBlocksMissing(),
			                                               session.getElapsedTime())
			                                    .withStyle(style -> style
					                                    .withClickEvent(new ClickEvent.SuggestCommand("/servux materials cancel " + session.getSessionId()))
					                                    .withHoverEvent(new HoverEvent.ShowText(
							                                    StringUtils.translate("servux.litematics.materials.hover.cancel")))),
			                   false);
		}

		return running.size();
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx, @Nullable String sessionId) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		MaterialListSession session;

		if (sessionId != null)
		{
			try
			{
				session = ServerTaskSessionManager.INSTANCE.get(UUID.fromString(sessionId), MaterialListSession.class);
			}
			catch (IllegalArgumentException e)
			{
				throw StringUtils.translateError("servux.litematics.materials.error.bad_session", sessionId);
			}
		}
		else
		{
			session = (MaterialListSession) ServerTaskSessionManager.INSTANCE.getRunningFor(ownerOf(source), ServerTaskKind.MATERIALS);
		}

		if (session == null || !session.isRunning())
		{
			throw StringUtils.translateError("servux.litematics.materials.error.no_session");
		}

		final MaterialListSession cancelled = session;

		cancelled.cancel();

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.cancelled", cancelled.getPlacementName()), true);

		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		MaterialListSession session = (MaterialListSession) ServerTaskSessionManager.INSTANCE.getLatestFor(ownerOf(source), ServerTaskKind.MATERIALS);

		if (session == null)
		{
			throw StringUtils.translateError("servux.litematics.materials.error.no_result");
		}

		if (session.isRunning())
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.materials.show.still_running").withStyle(ChatFormatting.YELLOW), false);
		}

		List<Component> lines = report(session.getResult(), page, DEFAULT_PAGE_SIZE);

		for (Component line : lines)
		{
			source.sendSuccess(() -> line, false);
		}

		return lines.size();
	}

	/**
	 * One page of the list, most still needed first.
	 * <p>
	 * Block states are reported as states rather than as the items they cost: turning one
	 * into the other is what Litematica's client side {@code MaterialCache} does, and it has
	 * no server equivalent. A player reading this in chat wants to know what is still
	 * missing, which the state answers just as well.
	 */
	private static List<Component> report(MaterialListResult result, int page, int pageSize)
	{
		List<Component> lines = new ArrayList<>();

		// Ordered by what is still missing, since that is the part anyone acts on. A state
		// that is fully placed already sorts to the bottom rather than out of the list.
		Object2IntOpenHashMap<BlockState> missing = result.getCountsMissing();
		List<BlockState> states = new ArrayList<>(result.getCountsTotal().keySet());
		states.sort(Comparator.comparingInt(missing::getInt).reversed());

		final int totalPages = Math.max(1, (states.size() + pageSize - 1) / pageSize);
		final int clamped = Math.min(page, totalPages);
		final int start = (clamped - 1) * pageSize;
		final int end = Math.min(start + pageSize, states.size());

		lines.add(StringUtils.translate("servux.litematics.materials.report.header", clamped, totalPages, states.size()));

		for (int i = start; i < end; i++)
		{
			BlockState state = states.get(i);

			lines.add(StringUtils.translate("servux.litematics.materials.report.block",
			                                state.getBlock().getName(),
			                                missing.getInt(state),
			                                result.getCountsTotal().getInt(state)));
		}

		// The two extra tallies are short enough to print whole, and only on the last page
		if (clamped == totalPages)
		{
			appendIdCounts(lines, result.getEntityCounts(), "servux.litematics.materials.report.entity");
			appendIdCounts(lines, result.getContainerItemCounts(), "servux.litematics.materials.report.item");
		}

		return lines;
	}

	private static void appendIdCounts(List<Component> lines, Object2IntOpenHashMap<Identifier> counts, String key)
	{
		List<Map.Entry<Identifier, Integer>> entries = new ArrayList<>(counts.object2IntEntrySet().stream()
		                                                                    .map(e -> Map.entry(e.getKey(), e.getIntValue()))
		                                                                    .toList());

		entries.sort(Comparator.<Map.Entry<Identifier, Integer>>comparingInt(Map.Entry::getValue).reversed());

		for (Map.Entry<Identifier, Integer> entry : entries)
		{
			lines.add(StringUtils.translate(key, entry.getKey().toString(), entry.getValue()));
		}
	}
}
