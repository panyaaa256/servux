package fi.dy.masa.servux.commands;

import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;

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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import fi.dy.masa.servux.Reference;
import fi.dy.masa.servux.dataproviders.LitematicsDataProvider;
import fi.dy.masa.servux.interop.SyncmaticaBridge;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.verifier.VerifyMismatchType;
import fi.dy.masa.servux.schematic.verifier.VerifyReport;
import fi.dy.masa.servux.schematic.verifier.VerifySession;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.scheduler.session.ServerTaskSession;
import fi.dy.masa.servux.scheduler.session.ServerTaskSessionManager;
import fi.dy.masa.servux.util.PermissionsUtil;
import fi.dy.masa.servux.util.StringUtils;

/**
 * The {@code /servux verify} sub-tree.
 * <p>
 * Kept out of {@link ServuxCommand} so that the only change needed there is a single
 * {@code .then(...)}. The verification settings themselves need no command support: they
 * are picked up automatically by {@code /servux set|info|list|search} via the provider's
 * settings list.
 */
public class ServuxVerifyCommand
{
	public static ArgumentBuilder<CommandSourceStack, ?> build()
	{
		return Commands.literal("verify")
		               .requires(PermissionsUtil.require(Reference.MOD_ID + ".commands.verify", 4))
		               .then(Commands.literal("list").executes(ServuxVerifyCommand::listPlacements))
		               .then(Commands.literal("start")
		                             .then(Commands.argument("placement", StringArgumentType.greedyString())
		                                           .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
				                                           placementNames(ctx.getSource().getServer()), builder))
		                                           .executes(ServuxVerifyCommand::start)))
		               .then(Commands.literal("status").executes(ServuxVerifyCommand::status))
		               .then(Commands.literal("cancel")
		                             .executes(ctx -> cancel(ctx, null))
		                             .then(Commands.argument("session", StringArgumentType.string())
		                                           .executes(ctx -> cancel(ctx, StringArgumentType.getString(ctx, "session")))))
		               .then(Commands.literal("show")
		                             .then(Commands.argument("category", StringArgumentType.word())
		                                           .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
				                                           VerifyMismatchType.REPORTED.stream().map(VerifyMismatchType::getName).toList(), builder))
		                                           .executes(ctx -> show(ctx, 1))
		                                           .then(Commands.argument("page", IntegerArgumentType.integer(1))
		                                                         .executes(ctx -> show(ctx, IntegerArgumentType.getInteger(ctx, "page"))))));
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

	private static int listPlacements(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();

		if (!LitematicsDataProvider.INSTANCE.isSyncmaticaInteropEnabled())
		{
			throw StringUtils.translateError("servux.litematics.verify.error.interop_disabled");
		}

		MinecraftServer server = source.getServer();

		if (!SyncmaticaBridge.INSTANCE.isAvailable(server))
		{
			throw StringUtils.translateError("servux.litematics.verify.error.no_syncmatica");
		}

		List<SyncmaticaBridge.SyncmaticaPlacement> placements = SyncmaticaBridge.INSTANCE.getPlacements(server);

		if (placements.isEmpty())
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.list.empty"), false);
			return 0;
		}

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.list.header", placements.size()), false);

		for (SyncmaticaBridge.SyncmaticaPlacement placement : placements)
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.list.entry",
			                                               placement.displayName(),
			                                               placement.dimension().identifier().toString(),
			                                               placement.origin().toShortString())
			                                    .withStyle(style -> style
					                                    .withClickEvent(new ClickEvent.SuggestCommand("/servux verify start " + placement.displayName()))
					                                    .withHoverEvent(new HoverEvent.ShowText(
							                                    StringUtils.translate("servux.litematics.verify.hover.start", placement.id().toString())))),
			                   false);
		}

		return placements.size();
	}

	private static int start(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
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

		VerifySession session = LitematicsDataProvider.INSTANCE.startVerify(
				level, placement, null, ownerOf(source), source, null);

		if (session == null)
		{
			throw StringUtils.translateError("servux.litematics.verify.error.already_running");
		}

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.started",
		                                               placement.getName(),
		                                               level.dimension().identifier().toString(),
		                                               session.getResult().getTotalChunks()), false);

		return 1;
	}

	private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		List<VerifySession> running = ServerTaskSessionManager.INSTANCE.getAllRunning(ServerTaskKind.VERIFY)
		                                                     .stream()
		                                                     .map(VerifySession.class::cast)
		                                                     .toList();

		if (running.isEmpty())
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.status.none"), false);
			return 0;
		}

		for (VerifySession session : running)
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.status.entry",
			                                               session.getPlacementName(),
			                                               session.getResult().getProcessedChunks(),
			                                               session.getResult().getTotalChunks(),
			                                               session.getResult().getTotalMismatches(),
			                                               session.getElapsedTime())
			                                    .withStyle(style -> style
					                                    .withClickEvent(new ClickEvent.SuggestCommand("/servux verify cancel " + session.getSessionId()))
					                                    .withHoverEvent(new HoverEvent.ShowText(
							                                    StringUtils.translate("servux.litematics.verify.hover.cancel")))),
			                   false);
		}

		return running.size();
	}

	private static int cancel(CommandContext<CommandSourceStack> ctx, @Nullable String sessionId) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		VerifySession session;

		if (sessionId != null)
		{
			try
			{
				session = ServerTaskSessionManager.INSTANCE.get(UUID.fromString(sessionId), VerifySession.class);
			}
			catch (IllegalArgumentException e)
			{
				throw StringUtils.translateError("servux.litematics.verify.error.bad_session", sessionId);
			}
		}
		else
		{
			session = (VerifySession) ServerTaskSessionManager.INSTANCE.getRunningFor(ownerOf(source), ServerTaskKind.VERIFY);
		}

		if (session == null || !session.isRunning())
		{
			throw StringUtils.translateError("servux.litematics.verify.error.no_session");
		}

		final VerifySession cancelled = session;

		cancelled.cancel();

		source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.cancelled", cancelled.getPlacementName()), true);

		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException
	{
		CommandSourceStack source = ctx.getSource();
		String categoryName = StringArgumentType.getString(ctx, "category");
		VerifyMismatchType type = VerifyMismatchType.fromName(categoryName);

		if (type == null || !VerifyMismatchType.REPORTED.contains(type))
		{
			throw StringUtils.translateError("servux.litematics.verify.error.unknown_category", categoryName);
		}

		VerifySession session = (VerifySession) ServerTaskSessionManager.INSTANCE.getLatestFor(ownerOf(source), ServerTaskKind.VERIFY);

		if (session == null)
		{
			throw StringUtils.translateError("servux.litematics.verify.error.no_result");
		}

		if (session.isRunning())
		{
			source.sendSuccess(() -> StringUtils.translate("servux.litematics.verify.show.still_running").withStyle(ChatFormatting.YELLOW), false);
		}

		for (Component line : VerifyReport.details(session, type, page, VerifyReport.DEFAULT_PAGE_SIZE))
		{
			source.sendSuccess(() -> line, false);
		}

		return 1;
	}
}
