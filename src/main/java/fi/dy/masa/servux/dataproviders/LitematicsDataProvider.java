package fi.dy.masa.servux.dataproviders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.ApiStatus;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.servux.Reference;
import fi.dy.masa.servux.Servux;
import fi.dy.masa.servux.network.IPluginServerPlayHandler;
import fi.dy.masa.servux.network.ServerPlayHandler;
import fi.dy.masa.servux.network.packet.ServuxLitematicaHandler;
import fi.dy.masa.servux.network.packet.ServuxLitematicaPacket;
import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.scheduler.session.IResultBatcher;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.scheduler.session.ServerTaskSession;
import fi.dy.masa.servux.scheduler.session.ServerTaskSessionManager;
import fi.dy.masa.servux.scheduler.TaskScheduler;
import fi.dy.masa.servux.scheduler.tasks.TaskAnalyzeArea;
import fi.dy.masa.servux.scheduler.tasks.TaskDeleteArea;
import fi.dy.masa.servux.scheduler.tasks.TaskFillArea;
import fi.dy.masa.servux.scheduler.tasks.TaskMaterialListPlacement;
import fi.dy.masa.servux.scheduler.tasks.TaskPasteSchematicPerChunkBase;
import fi.dy.masa.servux.scheduler.tasks.TaskPasteSchematicPerChunkDirect;
import fi.dy.masa.servux.scheduler.tasks.TaskSaveSchematic;
import fi.dy.masa.servux.scheduler.tasks.TaskVerifySchematicPerChunk;
import fi.dy.masa.servux.schematic.LitematicaSchematic;
import fi.dy.masa.servux.schematic.analyzer.AnalyzeResult;
import fi.dy.masa.servux.schematic.analyzer.AnalyzeResultSerializer;
import fi.dy.masa.servux.schematic.analyzer.AnalyzeSession;
import fi.dy.masa.servux.schematic.materials.MaterialListResult;
import fi.dy.masa.servux.schematic.materials.MaterialListResultSerializer;
import fi.dy.masa.servux.schematic.materials.MaterialListSession;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.selection.AreaSelection;
import fi.dy.masa.servux.schematic.selection.Box;
import fi.dy.masa.servux.schematic.transmit.SchematicBufferManager;
import fi.dy.masa.servux.schematic.verifier.VerifyEntityMatcher;
import fi.dy.masa.servux.schematic.verifier.VerifyNbtComparator;
import fi.dy.masa.servux.schematic.verifier.VerifyReport;
import fi.dy.masa.servux.schematic.verifier.VerifyResult;
import fi.dy.masa.servux.schematic.verifier.VerifyResultSerializer;
import fi.dy.masa.servux.schematic.verifier.VerifySession;
import fi.dy.masa.servux.settings.IServuxSetting;
import fi.dy.masa.servux.settings.ServuxBoolSetting;
import fi.dy.masa.servux.settings.ServuxIntSetting;
import fi.dy.masa.servux.util.PasteLayerBehavior;
import fi.dy.masa.servux.util.AreaSelectionCodec;
import fi.dy.masa.servux.util.FileNameUtils;
import fi.dy.masa.servux.util.PermissionsUtil;
import fi.dy.masa.servux.util.ReplaceBehavior;
import fi.dy.masa.servux.util.StringUtils;
import fi.dy.masa.servux.util.chunk.ServerChunkLoader;
import fi.dy.masa.servux.util.data.Constants;
import fi.dy.masa.servux.util.data.tag.BaseData;
import fi.dy.masa.servux.util.data.tag.CompoundData;
import fi.dy.masa.servux.util.data.tag.ListData;
import fi.dy.masa.servux.util.data.tag.StringData;
import fi.dy.masa.servux.util.data.tag.converter.DataConverterNbt;
import fi.dy.masa.servux.util.data.tag.util.DataOps;
import fi.dy.masa.servux.util.data.tag.util.DataTypeUtils;
import fi.dy.masa.servux.util.game.EntityUtils;
import fi.dy.masa.servux.util.nbt.NbtView;
import fi.dy.masa.servux.util.position.LayerRange;
import fi.dy.masa.servux.util.position.PositionUtils;

public class LitematicsDataProvider extends DataProviderBase
{
	public static final LitematicsDataProvider INSTANCE = new LitematicsDataProvider();
	private final static ServuxLitematicaHandler<ServuxLitematicaPacket.Payload> HANDLER = ServuxLitematicaHandler.getInstance();
	private final CompoundData metadata = new CompoundData();
	private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0);
	private final ServuxIntSetting pastePermissionLevel = new ServuxIntSetting(this, "permission_level_paste", 0, 4, 0);
	private final ServuxIntSetting taskPermissionLevel = new ServuxIntSetting(this, "permission_level_tasks", 0, 4, 0);
	private final ServuxIntSetting verifyPermissionLevel = new ServuxIntSetting(this, "permission_level_verify", 0, 4, 0);
	private final ServuxIntSetting analyzePermissionLevel = new ServuxIntSetting(this, "permission_level_analyze", 0, 4, 0);
	private final ServuxIntSetting materialsPermissionLevel = new ServuxIntSetting(this, "permission_level_materials", 0, 4, 0);
	private final ServuxBoolSetting playerTaskFeedback = new ServuxBoolSetting(this, "player_task_feedback", false);
	public final ServuxBoolSetting fixRaiLRotations = new ServuxBoolSetting(this, "fix_rail_rotations", true);
	public final ServuxBoolSetting fixStairMirror = new ServuxBoolSetting(this, "fix_stairs_mirror", true);
	public final ServuxBoolSetting fixChestMirror = new ServuxBoolSetting(this, "fix_chest_mirror", true);
	public final ServuxBoolSetting deDuplicateSchematicEntities = new ServuxBoolSetting(this, "deduplicate_schematic_entities", false);
	public final ServuxIntSetting verifyMaxResultPositions = new ServuxIntSetting(this, "verify_max_result_positions", 200000, 10000000, 0);
	public final ServuxIntSetting taskBatchPositions = new ServuxIntSetting(this, "task_batch_positions", 16384, 262144, 256);
	public final ServuxIntSetting taskSessionTimeout = new ServuxIntSetting(this, "task_session_timeout", 300, 86400, 0);
	public final ServuxBoolSetting verifySyncmaticaInterop = new ServuxBoolSetting(this, "verify_syncmatica_interop", true);
	public final ServuxBoolSetting chunkWalkForceLoadChunks = new ServuxBoolSetting(this, "chunk_walk_force_load_chunks", true);
	public final ServuxBoolSetting chunkWalkGenerateMissingChunks = new ServuxBoolSetting(this, "chunk_walk_generate_missing_chunks", false);
	/**
	 * Shared by every chunk walking task, not just verification: a verify, an area
	 * analysis and a server side save all pull chunks in through the same loader, so
	 * they answer to the same budget.
	 */
	public final ServuxIntSetting chunkWalkMaxLoadsPerTick = new ServuxIntSetting(this, "chunk_walk_max_loads_per_tick", 2, 16, 1);
	public final ServuxIntSetting chunkWalkPauseMsptThreshold = new ServuxIntSetting(this, "chunk_walk_pause_mspt_threshold", 45, 1000, 0);
	public final ServuxBoolSetting verifyNbt = new ServuxBoolSetting(this, "verify_nbt", true);
	public final ServuxBoolSetting verifyNbtSlotExact = new ServuxBoolSetting(this, "verify_nbt_slot_exact", false);
	public final ServuxBoolSetting verifyNbtStrict = new ServuxBoolSetting(this, "verify_nbt_strict", false);
	/**
	 * How many Wrong Contents positions also carry both sides' block entity data back to the
	 * client, so that it can show what is actually wrong. A container's data can run to
	 * kilobytes (a chest of full shulker boxes), hence a cap of its own rather than
	 * verify_max_result_positions.
	 */
	public final ServuxIntSetting verifyNbtDetailPositions = new ServuxIntSetting(this, "verify_nbt_detail_positions", 1024, 65536, 0);
	/**
	 * Cap on the volume one analysis may cover, in blocks. An analysis reads every position
	 * in the area, so an unbounded selection is an unbounded amount of work; 0 lifts the cap.
	 * The default is a 512x256x512 region, which is already far beyond a normal build.
	 */
	public final ServuxIntSetting analyzeMaxVolume = new ServuxIntSetting(this, "analyze_max_volume", 67108864, Integer.MAX_VALUE, 0);
	/** Reading every container in an area is the expensive part; allow it to be turned off. */
	public final ServuxBoolSetting analyzeContainers = new ServuxBoolSetting(this, "analyze_containers", true);
	private final List<IServuxSetting<?>> settings = List.of(
			this.permissionLevel,
			this.pastePermissionLevel,
			this.taskPermissionLevel,
			this.verifyPermissionLevel,
			this.analyzePermissionLevel,
			this.materialsPermissionLevel,
			this.playerTaskFeedback,
			this.fixRaiLRotations,
			this.fixStairMirror,
			this.fixChestMirror,
			this.deDuplicateSchematicEntities,
			this.verifyMaxResultPositions,
			this.taskBatchPositions,
			this.taskSessionTimeout,
			this.verifySyncmaticaInterop,
			this.chunkWalkForceLoadChunks,
			this.chunkWalkGenerateMissingChunks,
			this.chunkWalkMaxLoadsPerTick,
			this.chunkWalkPauseMsptThreshold,
			this.verifyNbt,
			this.verifyNbtSlotExact,
			this.verifyNbtStrict,
			this.verifyNbtDetailPositions,
			this.analyzeMaxVolume,
			this.analyzeContainers
	);

	private final List<UUID> registeredPlayers = new ArrayList<>();
	private final List<UUID> invalidPlayers = new ArrayList<>();
	private final SchematicBufferManager bufferManager = new SchematicBufferManager();
	private final Path transmitDir;

	protected LitematicsDataProvider()
	{
		super("litematic_data",
		      ServuxLitematicaHandler.CHANNEL_ID,
		      ServuxLitematicaPacket.PROTOCOL_VERSION,
		      0, Reference.MOD_ID + ".provider.litematic_data",
		      "Litematics Data provider.");

		this.metadata.putString("name", this.getName());
		this.metadata.putString("id", this.getNetworkChannel().toString());
		this.metadata.putInt("version", this.getProtocolVersion());
		this.metadata.putString("servux", Reference.MOD_STRING);

		// Capability advertisement, deliberately separate from PROTOCOL_VERSION: bumping the
		// version would lock out every existing Litematica client, whereas clients that do not
		// know this key simply ignore it. Clients branch on the capability, not the version.
		ListData features = new ListData();
		features.add(new StringData("verify"));
		features.add(new StringData("verify_nbt"));
		features.add(new StringData("verify_entities"));
		features.add(new StringData(ServerTaskKind.ANALYZE.getName()));
		features.add(new StringData(ServerTaskKind.MATERIALS.getName()));
		this.metadata.put("Features", features);

		// Litematic-Transmit Dir
		this.transmitDir = this.getTransmitDir();
	}

	@Override
	public List<IServuxSetting<?>> getSettings()
	{
		return settings;
	}

	@Override
	public void registerHandler()
	{
		ServerPlayHandler.getInstance().registerServerPlayHandler(HANDLER);

		if (!this.isRegistered())
		{
			HANDLER.registerPlayPayload(ServuxLitematicaPacket.Payload.ID, ServuxLitematicaPacket.Payload.CODEC, IPluginServerPlayHandler.BOTH_SERVER);
			this.setRegistered(true);
		}

		HANDLER.registerPlayReceiver(ServuxLitematicaPacket.Payload.ID, HANDLER::receivePlayPayload);
	}

	@Override
	public void unregisterHandler()
	{
		HANDLER.unregisterPlayReceiver();
		ServerPlayHandler.getInstance().unregisterServerPlayHandler(HANDLER);
	}

	@Override
	public IPluginServerPlayHandler<?> getPacketHandler()
	{
		return HANDLER;
	}

	public SchematicBufferManager getBufferManager()
	{
		return this.bufferManager;
	}

	public Path getTransmitDir()
	{
		Path dir = this.transmitDir != null ? this.transmitDir : DataProviderManager.INSTANCE.getRootDir().resolve("schematics").normalize();

		if (!Files.exists(dir) || !Files.isDirectory(dir))
		{
			try
			{
				if (Files.exists(dir))
				{
					Files.delete(dir);
				}

				Files.createDirectory(dir);
				Servux.LOGGER.warn("getTransmitDir(): Created schematic transmit directory '{}'", dir.toAbsolutePath().toString());
			}
			catch (IOException err)
			{
				Servux.LOGGER.error("getTransmitDir(): Fatal exception creating schematic transmit dir '{}'; {}", dir.toAbsolutePath().toString(), err.getLocalizedMessage());
				throw new RuntimeException(err);
			}
		}

		if (!Files.isWritable(dir))
		{
			Servux.LOGGER.error("Schematic transmit directory '{}'; is not writeable.", dir.toAbsolutePath().toString());
		}

		Servux.debugLog("getTransmitDir(): Schematic transmit directory debug '{}'", dir.toAbsolutePath().toString());

		return dir;
	}

	@Override
	public boolean isPlayerRegistered(ServerPlayer player)
	{
		return this.registeredPlayers.contains(player.getUUID()) && !this.isPlayerInvalid(player);
	}

	@Override
	public void register(ServerPlayer player, CompoundData tags)
	{
		if (!this.isEnabled()) { return; }
		UUID uuid = player.getUUID();

		if (tags == null || tags.getIntOrDefault("version", -1) < this.getProtocolVersion())
		{
			Servux.LOGGER.warn("litematic_data: Denying access for player {}, Insufficient Protocol Version; This Server Requires: Version {}", player.getName().tryCollapseToString(), this.getProtocolVersion());
			player.sendSystemMessage(StringUtils.translate("servux.general.error.protocol_version_too_low", this.getName()));
			HANDLER.tickFailures(player);
			return;
		}

		if (!this.hasPermission(player))
		{
			// No Permission
			Servux.debugLog("litematic_data: Denying access for player {}, Insufficient Permissions", player.getName().tryCollapseToString());
			return;
		}

		CompoundData nbt = new CompoundData();
		nbt.combine(this.metadata);

		Servux.debugLog("litematic_data: sendMetadata to player {}", player.getName().tryCollapseToString());
		this.registeredPlayers.add(uuid);

		// Sends Metadata handshake, it doesn't succeed the first time, so using networkHandler
		if (player.connection != null)
		{
			HANDLER.sendPlayPayload(player.connection, new ServuxLitematicaPacket.Payload(ServuxLitematicaPacket.MetadataResponse(nbt)));
		}
		else
		{
			HANDLER.sendPlayPayload(player, new ServuxLitematicaPacket.Payload(ServuxLitematicaPacket.MetadataResponse(nbt)));
		}
	}

	@Override
	public void unregister(ServerPlayer player, @Nullable CompoundData tags)
	{
		if (this.isEnabled())
		{
			Servux.debugLog("litematic_data: Unregistered player {}", player.getName().tryCollapseToString());
		}

		UUID uuid = player.getUUID();

		HANDLER.resetFailures(this.getNetworkChannel(), player);
		this.getBufferManager().removePlayer(player);
		this.registeredPlayers.remove(uuid);
	}

	@Override
	public void onPacketFailure(ServerPlayer player)
	{
		UUID uuid = player.getUUID();
		this.setPlayerInvalid(player);
		this.registeredPlayers.remove(uuid);
	}

	@Override
	public void removePlayer(ServerPlayer player)
	{
		UUID uuid = player.getUUID();
		this.removeInvalidPlayer(player);
		this.registeredPlayers.remove(uuid);
		HANDLER.resetFailures(this.getNetworkChannel(), player);
	}

	private void setPlayerInvalid(ServerPlayer player)
	{
		UUID uuid = player.getUUID();

		if (!this.invalidPlayers.contains(uuid))
		{
			this.invalidPlayers.add(uuid);
		}
	}

	private boolean isPlayerInvalid(ServerPlayer player)
	{
		return this.invalidPlayers.contains(player.getUUID());
	}

	private void removeInvalidPlayer(ServerPlayer player)
	{
		this.invalidPlayers.remove(player.getUUID());
	}

	/**
	 * Handles the C2S task requests: the fire-and-forget ones the task manager runs
	 * (Fill, Delete), and the small control messages that drive a session's result
	 * stream - the batch acknowledgements that pull the next batch out of the server.
	 * <p>
	 * Which kind of session is being acknowledged is decided by the {@code Task} string
	 * alone; an unrecognised one is dropped rather than guessed at.
	 */
	@ApiStatus.Experimental
	public void onTaskRequest(ServerPlayer player, CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
		{
			return;
		}

		final String taskType = tags.getStringOrDefault("Task", "");
		final long timeStart = System.currentTimeMillis();
		ServerLevel level = player.level();
		Servux.debugLog("litematic_data: Received TaskRequest from player {} of type: [{}]", player.getName().getString(), taskType);

		// A batch acknowledgement for one of the session driven tasks. Those carry their own
		// dispatch because a session, not a Task string, decides what happens next.
		ServerTaskKind kind = ServerTaskKind.byAckTask(taskType);

		if (kind != null)
		{
			this.onSessionAck(player, tags, kind);
			return;
		}

		switch (taskType)
		{
			case "Fill" ->
			{
				if (!this.hasPermissionsForTask(player, "fill"))
				{
					Servux.debugLog("litematic_data: Denying onTaskRequest from player {}, Insufficient Permissions for Fill Task.", player.getName().getString());
					player.sendSystemMessage(StringUtils.translate("servux.litematics.error.insufficent_for_tasks"));

					return;
				}

				if (!player.isCreative())
				{
					Servux.debugLog("litematic_data: Denying Litematic Task Request for player {}, Player is not in Creative Mode.", player.getName().tryCollapseToString());
					player.sendSystemMessage(StringUtils.translate("servux.litematics.error.creative_required_for_task"));
					return;
				}

				ListData list = tags.getListOrDefault("Boxes", Constants.NBT.TAG_COMPOUND, new ListData());
				List<Box> boxes = new ArrayList<>();

				for (int i = 0; i < list.size(); i++)
				{
					BaseData entry = list.get(i);

					if (entry != null && !entry.isEmpty())
					{
						Box.CODEC.parse(DataOps.INSTANCE, entry).resultOrPartial().ifPresent(boxes::add);
					}
				}

				BlockState fillState = tags.getCodec("FillState", BlockState.CODEC).orElse(null);

				if (fillState == null)
				{
					if (this.shouldSendPlayerTaskFeedback())
					{
						player.sendSystemMessage(StringUtils.translate("servux.litematics.task.fill_area.no_fill_state"));
					}

					return;
				}

				if (boxes.isEmpty())
				{
					if (this.shouldSendPlayerTaskFeedback())
					{
						player.sendSystemMessage(StringUtils.translate("servux.litematics.task.fill_area.no_boxes"));
					}

					return;
				}

				final BlockState replaceState = tags.getCodec("ReplaceState", BlockState.CODEC).orElse(null);
				final boolean removeEntities = tags.getBooleanOrDefault("RemoveEntities", false);
				final int interval = tags.getIntOrDefault("Interval", 1);
				TaskContext ctx = new TaskContext(level.getServer(), level, player, "Fill", timeStart);
				TaskFillArea task = new TaskFillArea(ctx, boxes, fillState, replaceState, removeEntities);
				TaskScheduler.getInstance().scheduleTask(task, interval);
			}
			case "Delete" ->
			{
				if (!this.hasPermissionsForTask(player, "delete"))
				{
					Servux.debugLog("litematic_data: Denying onTaskRequest from player {}, Insufficient Permissions for Delete Task", player.getName().getString());
					player.sendSystemMessage(StringUtils.translate("servux.litematics.error.insufficent_for_tasks"));

					return;
				}

				if (!player.isCreative())
				{
					Servux.debugLog("litematic_data: Denying Litematic Task Request for player {}, Player is not in Creative Mode.", player.getName().tryCollapseToString());
					player.sendSystemMessage(StringUtils.translate("servux.litematics.error.creative_required_for_task"));
					return;
				}

				ListData list = tags.getListOrDefault("Boxes", Constants.NBT.TAG_COMPOUND, new ListData());
				List<Box> boxes = new ArrayList<>();

				for (int i = 0; i < list.size(); i++)
				{
					BaseData entry = list.get(i);

					if (entry != null && !entry.isEmpty())
					{
						Box.CODEC.parse(DataOps.INSTANCE, entry).resultOrPartial().ifPresent(boxes::add);
					}
				}

				if (boxes.isEmpty())
				{
					if (this.shouldSendPlayerTaskFeedback())
					{
						player.sendSystemMessage(StringUtils.translate("servux.litematics.task.fill_area.no_boxes"));
					}

					return;
				}

				final boolean removeEntities = tags.getBooleanOrDefault("RemoveEntities", false);
				final int interval = tags.getIntOrDefault("Interval", 1);
				TaskContext ctx = new TaskContext(level.getServer(), level, player, "Delete", timeStart);
				TaskDeleteArea task = new TaskDeleteArea(ctx, boxes, removeEntities);
				TaskScheduler.getInstance().scheduleTask(task, interval);
			}
			// TODO (Ensure Safe Transmit)
//			case "Save" ->
//			{
//				AreaSelection selection = tags.getCodec("AreaSelection", AreaSelection.CODEC).orElse(null);
//				final boolean visibleOnly = tags.getBooleanOrDefault("VisibleOnly", false);
//				final boolean ignoreEntities = tags.getBooleanOrDefault("IgnoreEntities", false);
//
//				if (selection == null)
//				{
//					if (this.shouldSendPlayerTaskFeedback())
//					{
//						player.sendSystemMessage(StringUtils.translate("servux.litematics.task.save.no_area_selection"));
//					}
//
//					return;
//				}
//
//				final String fileName = UUID.randomUUID().toString() + ".litematic";
//				final LitematicaSchematic.SchematicSaveInfo info = new LitematicaSchematic.SchematicSaveInfo(visibleOnly, ignoreEntities);
//				LitematicaSchematic schematic = LitematicaSchematic.createEmptySchematic(selection, player.getName().getString());
//
//				Runnable whenDone = () ->
//				{
//				};
//
//				TaskContext ctx = new TaskContext(level.getServer(), level, player, "Save", timeStart, whenDone);
//				TaskSaveSchematic task = new TaskSaveSchematic(ctx, this.transmitDir, fileName, schematic, selection, info, false);
//				TaskScheduler.getInstance().scheduleTask(task, 1);
//			}
			default ->
			{
				if (this.shouldSendPlayerTaskFeedback())
				{
					player.sendSystemMessage(StringUtils.translate("servux.litematics.task.invalid"));
				}
			}
		}
	}

	/** Pulls the next result batch out of a session, once the client says it took the last. */
	private void onSessionAck(ServerPlayer player, CompoundData tags, ServerTaskKind kind)
	{
		if (!this.hasPermissionFor(player, kind))
		{
			Servux.debugLog("litematic_data: Denying onTaskRequest ({}) from player {}, Insufficient Permissions.", kind.getName(), player.getName().getString());
			return;
		}

		ServerTaskSession session = this.getOwnedSession(player, tags, kind);

		if (session == null)
		{
			return;
		}

		// -1 rather than getInt()'s 0, so that "absent" stays distinguishable from batch 0
		session.setAcknowledgedBatch(tags.contains("Batch", Constants.NBT.TAG_INT) ? tags.getInt("Batch") : -1);
		this.sendNextBatch(player, session);
	}

	/**
	 * Forwards a task's progress to its player. Called from inside the server by
	 * {@code InfoHudSync}, not by a client - the C2S side of the task protocol is
	 * {@link #onTaskRequest}.
	 */
	public void onTaskStatusSync(ServerPlayer player, CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() ||
			tags == null || tags.isEmpty())
		{
			return;
		}

		if (!this.hasPermission(player))
		{
			Servux.debugLog("litematic_data: Denying onTaskStatusSync to player {}, Insufficient Permissions.", player.getName().getString());
			return;
		}

		HANDLER.encodeServerData(player, ServuxLitematicaPacket.TaskStatusSync(tags));
	}

	/** Handles a client asking to abandon one of its runs. */
	@ApiStatus.Experimental
	public void onTaskCancel(ServerPlayer player, CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
		{
			return;
		}

		ServerTaskKind kind = ServerTaskKind.byCancelTask(tags.getStringOrDefault("Task", ""));

		if (kind == null)
		{
			return;
		}

		if (!this.hasPermissionFor(player, kind))
		{
			Servux.debugLog("litematic_data: Denying onTaskCancel ({}) from player {}, Insufficient Permissions.", kind.getName(), player.getName().getString());
			return;
		}

		ServerTaskSession session = this.getOwnedSession(player, tags, kind);

		if (session != null)
		{
			Servux.debugLog("litematic_data: {} session {} cancelled by the client", kind.getName(), session.getSessionId());
			session.cancel();
			ServerTaskSessionManager.INSTANCE.remove(session.getSessionId());
		}
	}

	/**
	 * Looks a session up and checks it belongs to the asking player and is of the kind the
	 * message claims, so that one client cannot drive or cancel another's run - or steer a
	 * reply for one kind of task into a session of another.
	 */
	@Nullable
	private ServerTaskSession getOwnedSession(ServerPlayer player, CompoundData tags, ServerTaskKind kind)
	{
		UUID sessionId = VerifyResultSerializer.uuidFromIntArray(tags.getIntArray("SessionId"));

		if (sessionId == null)
		{
			return null;
		}

		ServerTaskSession session = ServerTaskSessionManager.INSTANCE.get(sessionId);

		return session != null && session.getKind() == kind && session.getOwner().equals(player.getUUID())
		       ? session : null;
	}

	/**
	 * The player a chunk walking task will run for.
	 * <p>
	 * A task needs one: {@code TaskBase} reads the player's position to sort chunks
	 * closest-first, and the progress pings go to that player. A command gives one through
	 * its source; a packet driven request has no source at all, so the requester - who by
	 * definition is a player, since the packet came from them - is looked up by owner
	 * instead. Without that, every packet driven verify, analysis and material list would
	 * refuse to start.
	 *
	 * @return null for a console or command block source, which has no player either way
	 */
	@Nullable
	private ServerPlayer taskPlayerFor(ServerLevel level, UUID owner, @Nullable CommandSourceStack source)
	{
		if (source != null)
		{
			return source.getPlayer();
		}

		return owner.equals(ServerTaskSession.CONSOLE_OWNER)
		       ? null : level.getServer().getPlayerList().getPlayer(owner);
	}

	/** The permission gate for each kind of server side task. */
	private boolean hasPermissionFor(ServerPlayer player, ServerTaskKind kind)
	{
		return switch (kind)
		{
			case VERIFY -> this.hasPermissionsForVerify(player);
			case ANALYZE -> this.hasPermissionsForAnalyze(player);
			case MATERIALS -> this.hasPermissionsForMaterials(player);
		};
	}

	/**
	 * Called when a walk finishes: switches the session over to handing out batches.
	 *
	 * @param batcher the encoder for this kind of result; built here rather than earlier so
	 *                that a run nobody is waiting for never pays for one
	 */
	private void beginStreaming(ServerTaskSession session, Supplier<IResultBatcher> batcher)
	{
		ServerPlayer player = session.getPlayer();

		if (player == null)
		{
			// The requester left; nothing to stream to
			ServerTaskSessionManager.INSTANCE.remove(session.getSessionId());
			return;
		}

		if (session.getState() == ServerTaskSession.State.CANCELLED)
		{
			ServerTaskSessionManager.INSTANCE.remove(session.getSessionId());
			return;
		}

		session.setBatcher(batcher.get());
		session.setState(ServerTaskSession.State.STREAMING);

		this.sendNextBatch(player, session);
	}

	/** Called when a verification finishes. */
	private void beginVerifyStreaming(VerifySession session)
	{
		this.beginStreaming(session, () -> new VerifyResultSerializer(session.getResult()));
	}

	/** Called when an area analysis finishes. */
	private void beginAnalyzeStreaming(AnalyzeSession session)
	{
		this.beginStreaming(session, () -> new AnalyzeResultSerializer(session.getResult()));
	}

	/** Called when a material list finishes. */
	private void beginMaterialListStreaming(MaterialListSession session)
	{
		this.beginStreaming(session, () -> new MaterialListResultSerializer(session.getResult()));
	}

	/**
	 * Sends one result batch. The client acknowledges it and that pulls the next one, so
	 * a result of any size crosses the wire a bounded amount at a time and a client that
	 * stops responding simply stops the flow (and is eventually reaped by the timeout).
	 */
	private void sendNextBatch(ServerPlayer player, ServerTaskSession session)
	{
		IResultBatcher batcher = session.getBatcher();

		if (batcher == null || session.getState() != ServerTaskSession.State.STREAMING)
		{
			return;
		}

		if (!batcher.hasMore() && batcher.getBatchNumber() > 0)
		{
			// The final batch has been acknowledged; the session has served its purpose
			session.setState(ServerTaskSession.State.DONE);
			ServerTaskSessionManager.INSTANCE.remove(session.getSessionId());
			return;
		}

		CompoundData batch = batcher.nextBatch(this.taskBatchPositions.getValue(), session.getSessionId());

		HANDLER.encodeServerData(player, ServuxLitematicaPacket.ResponseS2CStart(batch));
	}

	/**
	 * Progress ping, so the client's GUI can show something while it waits.
	 *
	 * @param extra adds the fields only this kind of task reports, or null for none
	 */
	public void sendTaskStatus(ServerTaskSession session, @Nullable Consumer<CompoundData> extra)
	{
		ServerPlayer player = session.getPlayer();

		if (player == null)
		{
			return;
		}

		CompoundData tag = new CompoundData();
		tag.putString("Task", session.getKind().statusTask());
		tag.putIntArray("SessionId", VerifyResultSerializer.uuidToIntArray(session.getSessionId()));
		tag.putInt("ChunksDone", session.getProgress().getProcessedChunks());
		tag.putInt("ChunksTotal", session.getProgress().getTotalChunks());

		if (extra != null)
		{
			extra.accept(tag);
		}

		HANDLER.encodeServerData(player, ServuxLitematicaPacket.TaskStatusSync(tag));
	}

	/** Progress ping for a verification, which also reports the mismatches found so far. */
	public void sendVerifyStatus(VerifySession session)
	{
		this.sendTaskStatus(session, tag -> tag.putInt("Mismatches", session.getResult().getTotalMismatches()));
	}

	/** Progress ping for a material list, which also reports what is still missing so far. */
	public void sendMaterialListStatus(MaterialListSession session)
	{
		this.sendTaskStatus(session, tag -> tag.putLong("Missing", session.getResult().getBlocksMissing()));
	}

	/** Reports a failure as a translation key, so the client renders it in its own language. */
	private void sendTaskError(ServerPlayer player, ServerTaskKind kind, @Nullable int[] sessionId, String key)
	{
		CompoundData tag = new CompoundData();
		tag.putString("Task", kind.errorTask());

		if (sessionId != null && sessionId.length == 4)
		{
			tag.putIntArray("SessionId", sessionId);
		}

		tag.putString("Key", key);

		HANDLER.encodeServerData(player, ServuxLitematicaPacket.TaskResponse(tag));
	}

	public void onBlockEntityRequest(ServerPlayer player, BlockPos pos, @Nullable CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled())
		{
			return;
		}

		if (!this.hasPermission(player))
		{
			Servux.debugLog("litematic_data: Denying onBlockEntityRequest from player {}, Insufficient Permissions.", player.getName().getString());
			return;
		}

		//Servux.logger.warn("LitematicsDataProvider#onBlockEntityRequest(): from player {}", player.getName().getLiteralString());
		BlockEntity be = player.level().getBlockEntity(pos);

		if (be != null)
		{
			CompoundData nbt = DataConverterNbt.fromVanillaCompound(be.saveWithFullMetadata(player.registryAccess()));
			HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleBlockResponse(pos, nbt));
		}
	}

	public void onEntityRequest(ServerPlayer player, int entityId, @Nullable CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled())
		{
			return;
		}

		if (!this.hasPermission(player))
		{
			Servux.debugLog("litematic_data: Denying onEntityRequest from player {}, Insufficient Permissions.", player.getName().getString());
			return;
		}

		//Servux.logger.warn("LitematicsDataProvider#onEntityRequest(): from player {} // entityId [{}]", player.getName().getLiteralString(), entityId);
		Entity entity = player.level().getEntity(entityId);

		if (entity != null)
		{
			NbtView view = NbtView.getWriter(player.level().registryAccess());
			Identifier id = EntityType.getKey(entity.getType());

			entity.saveWithoutId(view.getWriter());
			CompoundData nbt = view.readData();

			if (nbt != null && id != null)
			{
				if (entity.getType() == EntityTypes.PLAYER && !entity.getUUID().equals(player.getUUID()))
				{
					if (!EntitiesDataProvider.INSTANCE.hasPlayerInventoryPermission(player))
					{
						nbt.remove("Inventory");
						nbt.put("Inventory", new ListData());
					}
					if (!EntitiesDataProvider.INSTANCE.hasPlayerEnderItemsPermission(player))
					{
						nbt.remove("EnderItems");
						nbt.put("EnderItems", new ListData());
					}
				}

				nbt.putString("id", id.toString());
				HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleEntityResponse(entityId, nbt));
			}
		}
	}

	public void onBulkEntityRequest(ServerPlayer player, ChunkPos chunkPos, CompoundData req)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() || req == null || req.isEmpty())
		{
			return;
		}

		if (!this.hasPermission(player))
		{
			Servux.LOGGER.warn("litematic_data: Denying Litematic onBulkEntityRequest from player {}, Insufficient Permissions.", player.getName().getString());
			player.sendSystemMessage(StringUtils.translate("servux.litematics.error.bulk_request.insufficent"));
			return;
		}

		UUID uuid = player.getUUID();
		ServerLevel world = player.level();
		LevelChunk chunk = world != null ? world.getChunkSource().getChunkNow(chunkPos.x(), chunkPos.z()) : null;

		if (chunk == null)
		{
			if (this.shouldSendPlayerTaskFeedback())
			{
				player.sendSystemMessage(StringUtils.translate("servux.litematics.error.bulk_request.chunk_not_loaded", chunkPos.toString()));
			}

			return;
		}

		if ((req.contains("Task", Constants.NBT.TAG_STRING) &&
			req.getStringOrDefault("Task", "").equals("BulkEntityRequest")))
		{
			Servux.debugLog("litematic_data: Sending Bulk NBT Data for ChunkPos {} to player {}", chunkPos.toString(), player.getName().tryCollapseToString());
			final long timeStart = System.currentTimeMillis();
			ListData tileList = new ListData();
			ListData entityList = new ListData();
			final int minY = req.getIntOrDefault("minY", world.getMinY());
			final int maxY = req.getIntOrDefault("maxY", world.getMaxY());
			final BlockPos pos1 = new BlockPos(chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ());
			final BlockPos pos2 = new BlockPos(chunkPos.getMaxBlockX(), maxY, chunkPos.getMaxBlockZ());
			AABB bb = PositionUtils.createEnclosingAABB(pos1, pos2);
			Set<BlockPos> teSet = chunk.getBlockEntitiesPos();
			List<Entity> entities = world.getEntities((Entity) null, bb, EntityUtils.NOT_PLAYER);

			for (BlockPos tePos : teSet)
			{
				if ((tePos.getX() < chunkPos.getMinBlockX() || tePos.getX() > chunkPos.getMaxBlockX()) ||
					(tePos.getZ() < chunkPos.getMinBlockZ() || tePos.getZ() > chunkPos.getMaxBlockZ()) ||
					(tePos.getY() < minY || tePos.getY() > maxY))
				{
					continue;
				}

				BlockEntity be = world.getBlockEntity(tePos);

				if (be != null)
				{
					CompoundData beTag = DataConverterNbt.fromVanillaCompound(be.saveWithFullMetadata(player.registryAccess()));
					tileList.add(beTag);
				}
			}

			for (Entity entity : entities)
			{
				NbtView view = NbtView.getWriter(player.level().registryAccess());
				Identifier id = EntityType.getKey(entity.getType());

				entity.saveWithoutId(view.getWriter());
				CompoundData entTag = view.readData();

				if (entTag != null && id != null)
				{
					Vec3 posVec = new Vec3(entity.getX() - pos1.getX(), entity.getY() - pos1.getY(), entity.getZ() - pos1.getZ());
					entTag.putString("id", id.toString());

//					NbtUtils.writeEntityPositionToTag(posVec, entTag);
					DataTypeUtils.writeVec3dToListTag(entTag, posVec);
					entTag.putInt("entityId", entity.getId());
					entityList.add(entTag);
				}
			}

			CompoundData output = new CompoundData();

			output.putString("Task", "BulkEntityReply");
			output.put("TileEntities", tileList.copy());
			output.put("Entities", entityList.copy());
			output.putInt("chunkX", chunkPos.x());
			output.putInt("chunkZ", chunkPos.z());

			HANDLER.encodeServerData(player, ServuxLitematicaPacket.ResponseS2CStart(output));

			if (this.shouldSendPlayerTaskFeedback())
			{
				final long timeElapsed = System.currentTimeMillis() - timeStart;
				player.sendSystemMessage(
						StringUtils.translate("servux.litematics.feedback.bulk_request.acknowledge",
						                      world.dimension().identifier().toString(), chunkPos.toString(),
						                      tileList.size(), entityList.size(),
						                      timeElapsed), false
				);
			}
		}
	}

	public void handleClientPasteRequest(ServerPlayer player, CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
		{
			return;
		}

		if (!this.hasPermission(player) || !this.hasPermissionsForPaste(player))
		{
			Servux.debugLog("litematic_data: Denying Litematic Paste for player {}, Insufficient Permissions.", player.getName().tryCollapseToString());
			player.sendSystemMessage(StringUtils.translate("servux.litematics.error.insufficent_for_paste"));
			return;
		}
		if (!player.isCreative())
		{
			Servux.debugLog("litematic_data: Denying Litematic Paste for player {}, Player is not in Creative Mode.", player.getName().tryCollapseToString());
			player.sendSystemMessage(StringUtils.translate("servux.litematics.error.creative_required"));
			return;
		}

		if (tags.getStringOrDefault("Task", "").equals("LitematicaPaste"))
		{
			Servux.debugLog("litematic_data: Servux Paste request from player {}", player.getName().tryCollapseToString());
			final long timeStart = System.currentTimeMillis();
			SchematicPlacement placement = SchematicPlacement.createFromData(tags);
			ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOrDefault("ReplaceMode", ReplaceBehavior.NONE.name()));
			PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOrDefault("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
			LayerRange layerRange = tags.getCodec("RenderLayerRange", LayerRange.CODEC).orElse(null);
			final boolean changedBlocksOnly = tags.getBooleanOrDefault("ChangedBlocksOnly", false);
			final boolean ignoreBlocks = tags.getBooleanOrDefault("IgnoreBlocks", false);
			final boolean ignoreEntities = tags.getBooleanOrDefault("IgnoreEntities", false);
			final int interval = tags.getIntOrDefault("Interval", 1);
			ServerLevel level = player.level();

			// New Task Scheduler Paste
			TaskContext ctx = new TaskContext(level.getServer(), level, player, placement.getName(), timeStart);
			TaskPasteSchematicPerChunkBase task = new TaskPasteSchematicPerChunkDirect(ctx, Collections.singletonList(placement), layerRange, replaceMode, layerBehavior, changedBlocksOnly, ignoreBlocks, ignoreEntities);
			TaskScheduler.getInstance().scheduleTask(task, interval);
//				placement.pasteTo(level, replaceMode, layerBehavior, layerRange);

//			if (this.shouldSendPlayerTaskFeedback())
//			{
//				final long timeElapsed = System.currentTimeMillis() - timeStart;
//				player.sendSystemMessage(StringUtils.translate("servux.litematics.success.pasted", placement.getName(), player.level().dimension().identifier().toString(), timeElapsed), false);
//			}
		}
	}

	public void handleClientPasteRequestPair(ServerPlayer player, Pair<LitematicaSchematic, CompoundData> schemPair)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() ||
			schemPair == null || schemPair.getLeft() == null ||
			schemPair.getRight() == null || schemPair.getRight().isEmpty())
		{
			return;
		}

		if (!this.hasPermission(player) || !this.hasPermissionsForPaste(player))
		{
			Servux.debugLog("litematic_data: Denying Litematic Paste for player {}, Insufficient Permissions.", player.getName().tryCollapseToString());
			player.sendSystemMessage(StringUtils.translate("servux.litematics.error.insufficent_for_paste"));
			return;
		}
		if (!player.isCreative())
		{
			Servux.debugLog("litematic_data: Denying Litematic Paste for player {}, Player is not in Creative Mode.", player.getName().tryCollapseToString());
			player.sendSystemMessage(StringUtils.translate("servux.litematics.error.creative_required"));
			return;
		}

		CompoundData tags = schemPair.getRight();

		if (schemPair.getLeft() != null)
		{
			Servux.debugLog("litematic_data: Servux Paste (Pair) request from player {}", player.getName().tryCollapseToString());
			final long timeStart = System.currentTimeMillis();
			SchematicPlacement placement = SchematicPlacement.createFromData(schemPair.getLeft(), tags);
			ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOrDefault("ReplaceMode", ReplaceBehavior.NONE.name()));
			PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOrDefault("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
			LayerRange layerRange = tags.getCodec("RenderLayerRange", LayerRange.CODEC).orElse(null);
			final boolean changedBlocksOnly = tags.getBooleanOrDefault("ChangedBlocksOnly", false);
			final boolean ignoreBlocks = tags.getBooleanOrDefault("IgnoreBlocks", false);
			final boolean ignoreEntities = tags.getBooleanOrDefault("IgnoreEntities", false);
			final int interval = tags.getIntOrDefault("Interval", 1);
			ServerLevel level = player.level();

			// New Task Scheduler Paste
			TaskContext ctx = new TaskContext(level.getServer(), level, player, placement.getName(), timeStart);
			TaskPasteSchematicPerChunkBase task = new TaskPasteSchematicPerChunkDirect(ctx, Collections.singletonList(placement), layerRange, replaceMode, layerBehavior, changedBlocksOnly, ignoreBlocks, ignoreEntities);
			TaskScheduler.getInstance().scheduleTask(task, interval);
//			placement.pasteTo(level, replaceMode, layerBehavior, layerRange);

//			if (this.shouldSendPlayerTaskFeedback())
//			{
//				final long timeElapsed = System.currentTimeMillis() - timeStart;
//				player.sendSystemMessage(StringUtils.translate("servux.litematics.success.pasted", placement.getName(), player.level().dimension().identifier().toString(), timeElapsed), false);
//			}
		}
		else
		{
			// LitematicaSchematic == null could also be sus ?
			Servux.LOGGER.warn("handleClientPasteRequestPair: Error; Litematic provided by '{}' was null.", player.getName().tryCollapseToString());

			if (this.shouldSendPlayerTaskFeedback())
			{
				player.sendSystemMessage(StringUtils.translate("servux.litematics.error.pasting"), false);
			}
		}
	}

	/**
	 * Starts a server side verification of the given placement.
	 * <p>
	 * Verification is read-only, so unlike a paste it neither requires creative mode nor
	 * touches the world. Chunks that are not loaded are reported rather than force-loaded.
	 *
	 * @param owner      the requester, used to enforce one running session each
	 * @param source     the command source to report back to, or null for a packet request
	 * @param onComplete run on the server thread when the session ends; defaults to
	 *                   sending the summary to the requester
	 * @return the new session, or null if the requester already has one running
	 */
	@Nullable
	public VerifySession startVerify(ServerLevel level,
	                                 SchematicPlacement placement,
	                                 @Nullable LayerRange layerRange,
	                                 UUID owner,
	                                 @Nullable CommandSourceStack source,
	                                 @Nullable Consumer<VerifySession> onComplete)
	{
		return this.startVerify(level, placement, layerRange, owner, source, null, true,
		                        true, VerifyEntityMatcher.DEFAULT_TOLERANCE, onComplete);
	}

	/**
	 * @param sessionId       the id to run under, or null to mint one. A packet driven request
	 *                        supplies its own so it can match the replies to its request.
	 * @param compareContents whether the requester wants container contents compared at all;
	 *                        they only are when {@code verify_nbt} also allows it
	 * @param compareEntities whether to report the schematic's entities that are not in the world
	 * @param entityTolerance how far, in blocks, a world entity may be from where the schematic
	 *                        puts it and still count as that entity
	 */
	@Nullable
	public VerifySession startVerify(ServerLevel level,
	                                 SchematicPlacement placement,
	                                 @Nullable LayerRange layerRange,
	                                 UUID owner,
	                                 @Nullable CommandSourceStack source,
	                                 @Nullable UUID sessionId,
	                                 boolean compareContents,
	                                 boolean compareEntities,
	                                 double entityTolerance,
	                                 @Nullable Consumer<VerifySession> onComplete)
	{
		ServerPlayer player = this.taskPlayerFor(level, owner, source);

		if (player == null)
		{
			return null;
		}

		VerifyResult result = new VerifyResult(this.verifyMaxResultPositions.getValue(), this.verifyNbtDetailPositions.getValue());
		VerifySession session = new VerifySession(sessionId != null ? sessionId : UUID.randomUUID(),
		                                          owner, placement.getName(), level, result, source);

		if (!ServerTaskSessionManager.INSTANCE.add(session, this.taskSessionTimeout.getValue()))
		{
			return null;
		}

		TaskContext ctx = new TaskContext(level.getServer(), level, player, placement.getName(), System.currentTimeMillis());

		// A layer range only takes effect when the behavior asks for it; see shouldPasteBlock()
		PasteLayerBehavior layerBehavior = layerRange != null ? PasteLayerBehavior.RENDERED_ONLY : PasteLayerBehavior.ALL;

		ServerChunkLoader chunkLoader = this.chunkWalkForceLoadChunks.getValue()
		                              ? new ServerChunkLoader(level,
		                                                      this.chunkWalkGenerateMissingChunks.getValue(),
		                                                      this.chunkWalkMaxLoadsPerTick.getValue())
		                              : null;

		VerifyNbtComparator nbtComparator = this.verifyNbt.getValue() && compareContents
		                                  ? new VerifyNbtComparator(this.verifyNbtSlotExact.getValue(),
		                                                            this.verifyNbtStrict.getValue())
		                                  : null;

		// The client highlights the differing slots itself, so it has to compare the way we did
		result.setContentsComparison(this.verifyNbtSlotExact.getValue(), this.verifyNbtStrict.getValue());

		VerifyEntityMatcher entityMatcher = compareEntities ? new VerifyEntityMatcher(entityTolerance) : null;
		result.setEntitiesChecked(entityMatcher != null);

		TaskVerifySchematicPerChunk task = new TaskVerifySchematicPerChunk(
				ctx, Collections.singletonList(placement), layerRange, layerBehavior, result,
				chunkLoader, nbtComparator, entityMatcher, this.chunkWalkPauseMsptThreshold.getValue(), null);

		task.setOnProgress(() -> this.sendVerifyStatus(session));

		task.setOnComplete(() ->
		                   {
			                   // A cancel already moved the session out of RUNNING
			                   if (session.isRunning())
			                   {
				                   session.setState(VerifySession.State.DONE);
			                   }

			                   session.touch();

			                   if (onComplete != null)
			                   {
				                   onComplete.accept(session);
			                   }
			                   else
			                   {
				                   VerifyReport.summary(session).forEach(session::sendMessage);
			                   }
		                   });

		session.setTask(task);
		TaskScheduler.getInstance().scheduleTask(task, 1);

		Servux.debugLog("litematic_data: started verify session {} for placement '{}'", session.getSessionId(), placement.getName());

		return session;
	}

	/**
	 * Handles an inline {@code LitematicaVerify} request, i.e. a client that uploaded the
	 * schematic along with the placement.
	 * <p>
	 * The result is streamed back in acknowledged batches; see
	 * {@link #sendNextVerifyBatch}. Clients learn that this exists from the {@code verify}
	 * entry in the {@code Features} metadata list, not from the protocol version.
	 */
	public void handleClientVerifyRequest(ServerPlayer player, CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
		{
			return;
		}

		if (!this.hasPermissionsForVerify(player))
		{
			Servux.debugLog("litematic_data: Denying Litematic Verify for player {}, Insufficient Permissions.", player.getName().tryCollapseToString());
			this.sendTaskError(player, ServerTaskKind.VERIFY, tags.getIntArray("SessionId"), "servux.litematics.error.insufficent_for_verify");
			return;
		}

		SchematicPlacement placement;

		try
		{
			// createFromData() rethrows parse failures as unchecked, and this runs off a
			// packet, so a malformed payload must not escape into the network thread
			placement = SchematicPlacement.createFromData(tags);
		}
		catch (Exception e)
		{
			Servux.LOGGER.warn("litematic_data: failed to read the verify placement from player {}; {}", player.getName().tryCollapseToString(), e.getLocalizedMessage());
			placement = null;
		}

		int[] sessionIdArray = tags.getIntArray("SessionId");

		if (placement == null)
		{
			this.sendTaskError(player, ServerTaskKind.VERIFY, sessionIdArray, "servux.litematics.verify.error.bad_placement");
			return;
		}

		LayerRange layerRange = tags.getCodec("RenderLayerRange", LayerRange.CODEC).orElse(null);

		// The client picks the session id so that it can match replies to its own request
		UUID sessionId = VerifyResultSerializer.uuidFromIntArray(sessionIdArray);

		// A client that predates the option does not send it, and gets what it always got
		boolean compareContents = tags.contains("VerifyNbt", Constants.NBT.TAG_BYTE) == false || tags.getBoolean("VerifyNbt");

		// Entities, on the other hand, are only compared when asked for: a client that predates
		// them would have nowhere to show the result. The tolerance is the client's own
		// verifierEntityPositionTolerance, so that it matches what a local run would find.
		boolean compareEntities = tags.contains("VerifyEntities", Constants.NBT.TAG_BYTE) && tags.getBoolean("VerifyEntities");
		double entityTolerance = tags.contains("EntityTolerance", Constants.NBT.TAG_DOUBLE)
		                         ? tags.getDouble("EntityTolerance")
		                         : VerifyEntityMatcher.DEFAULT_TOLERANCE;

		VerifySession session = this.startVerify(player.level(), placement, layerRange, player.getUUID(), null,
		                                         sessionId, compareContents, compareEntities, entityTolerance,
		                                         this::beginVerifyStreaming);

		if (session == null)
		{
			this.sendTaskError(player, ServerTaskKind.VERIFY, sessionIdArray, "servux.litematics.verify.error.already_running");
		}
	}

	/**
	 * Starts a server side analysis of the given area.
	 * <p>
	 * Read-only, like a verification, and subject to the same chunk policy: chunks that were
	 * never generated are reported rather than generated.
	 *
	 * @param owner      the requester, used to enforce one running analysis each
	 * @param source     the command source to report back to, or null for a packet request
	 * @param sessionId  the id to run under, or null to mint one
	 * @param onComplete run on the server thread when the walk ends
	 * @return the new session, or null if the requester already has one running, or if the
	 *         area is larger than {@code analyze_max_volume} allows
	 */
	@Nullable
	public AnalyzeSession startAnalyze(ServerLevel level,
	                                   AreaSelection area,
	                                   @Nullable LayerRange layerRange,
	                                   boolean countEntities,
	                                   boolean countContainers,
	                                   UUID owner,
	                                   @Nullable CommandSourceStack source,
	                                   @Nullable UUID sessionId,
	                                   @Nullable Consumer<AnalyzeSession> onComplete)
	{
		if (this.exceedsAnalyzeVolume(area))
		{
			return null;
		}

		ServerPlayer player = this.taskPlayerFor(level, owner, source);

		if (player == null)
		{
			return null;
		}

		AnalyzeResult result = new AnalyzeResult();
		AnalyzeSession session = new AnalyzeSession(sessionId != null ? sessionId : UUID.randomUUID(),
		                                            owner, area.getName(), level, result, source);

		if (!ServerTaskSessionManager.INSTANCE.add(session, this.taskSessionTimeout.getValue()))
		{
			return null;
		}

		TaskContext ctx = new TaskContext(level.getServer(), level, player, area.getName(), System.currentTimeMillis());

		ServerChunkLoader chunkLoader = this.chunkWalkForceLoadChunks.getValue()
		                              ? new ServerChunkLoader(level,
		                                                      this.chunkWalkGenerateMissingChunks.getValue(),
		                                                      this.chunkWalkMaxLoadsPerTick.getValue())
		                              : null;

		TaskAnalyzeArea task = new TaskAnalyzeArea(ctx, area.getAllSubRegionBoxes(), layerRange, result, chunkLoader,
		                                           countEntities,
		                                           countContainers && this.analyzeContainers.getValue(),
		                                           this.chunkWalkPauseMsptThreshold.getValue(), null);

		task.setOnProgress(() -> this.sendTaskStatus(session, null));

		task.setOnComplete(() ->
		                   {
			                   // A cancel already moved the session out of RUNNING
			                   if (session.isRunning())
			                   {
				                   session.setState(ServerTaskSession.State.DONE);
			                   }

			                   session.touch();

			                   if (onComplete != null)
			                   {
				                   onComplete.accept(session);
			                   }
		                   });

		session.setTask(task);
		TaskScheduler.getInstance().scheduleTask(task, 1);

		Servux.debugLog("litematic_data: started analyze session {} for area '{}'", session.getSessionId(), area.getName());

		return session;
	}

	/**
	 * Handles a {@code LitematicaAnalyze} request: a client asking the server to tally an
	 * area it selected.
	 * <p>
	 * Clients learn that this exists from the {@code analyze} entry in the {@code Features}
	 * metadata list, not from the protocol version.
	 */
	public void handleClientAnalyzeRequest(ServerPlayer player, CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
		{
			return;
		}

		int[] sessionIdArray = tags.getIntArray("SessionId");

		if (!this.hasPermissionsForAnalyze(player))
		{
			Servux.debugLog("litematic_data: Denying Litematic Analyze for player {}, Insufficient Permissions.", player.getName().tryCollapseToString());
			this.sendTaskError(player, ServerTaskKind.ANALYZE, sessionIdArray, "servux.litematics.error.insufficent_for_analyze");
			return;
		}

		AreaSelection area = AreaSelectionCodec.read(tags);

		if (area == null)
		{
			this.sendTaskError(player, ServerTaskKind.ANALYZE, sessionIdArray, "servux.litematics.analyze.error.bad_area");
			return;
		}

		if (this.exceedsAnalyzeVolume(area))
		{
			this.sendTaskError(player, ServerTaskKind.ANALYZE, sessionIdArray, "servux.litematics.analyze.error.too_large");
			return;
		}

		LayerRange layerRange = tags.getCodec("RenderLayerRange", LayerRange.CODEC).orElse(null);

		// The client picks the session id so that it can match replies to its own request
		UUID sessionId = VerifyResultSerializer.uuidFromIntArray(sessionIdArray);

		AnalyzeSession session = this.startAnalyze(player.level(), area, layerRange,
		                                           tags.getBoolean("CountEntities"),
		                                           tags.getBoolean("CountContainers"),
		                                           player.getUUID(), null, sessionId, this::beginAnalyzeStreaming);

		if (session == null)
		{
			this.sendTaskError(player, ServerTaskKind.ANALYZE, sessionIdArray, "servux.litematics.analyze.error.already_running");
		}
	}

	/**
	 * Starts a server side material list for the given placement.
	 * <p>
	 * Read-only, like a verification, and subject to the same chunk policy: chunks that were
	 * never generated are reported rather than generated.
	 *
	 * @param ignoreState     do not count a block of the right type but the wrong state as
	 *                        missing, matching Litematica's {@code MATERIAL_LIST_IGNORE_STATE}
	 * @param countEntities   tally the entities the placement would spawn
	 * @param countContainers tally the contents of the containers the placement would place
	 * @param owner           the requester, used to enforce one running material list each
	 * @param source          the command source to report back to, or null for a packet request
	 * @param sessionId       the id to run under, or null to mint one. A packet driven request
	 *                        supplies its own so it can match the replies to its request.
	 * @param onComplete      run on the server thread when the walk ends
	 * @return the new session, or null if the requester already has one running
	 */
	@Nullable
	public MaterialListSession startMaterialList(ServerLevel level,
	                                             SchematicPlacement placement,
	                                             @Nullable LayerRange layerRange,
	                                             boolean ignoreState,
	                                             boolean countEntities,
	                                             boolean countContainers,
	                                             UUID owner,
	                                             @Nullable CommandSourceStack source,
	                                             @Nullable UUID sessionId,
	                                             @Nullable Consumer<MaterialListSession> onComplete)
	{
		ServerPlayer player = this.taskPlayerFor(level, owner, source);

		if (player == null)
		{
			return null;
		}

		MaterialListResult result = new MaterialListResult();
		MaterialListSession session = new MaterialListSession(sessionId != null ? sessionId : UUID.randomUUID(),
		                                                      owner, placement.getName(), level, result, source);

		if (!ServerTaskSessionManager.INSTANCE.add(session, this.taskSessionTimeout.getValue()))
		{
			return null;
		}

		TaskContext ctx = new TaskContext(level.getServer(), level, player, placement.getName(), System.currentTimeMillis());

		// A layer range only takes effect when the behavior asks for it; see shouldPasteBlock()
		PasteLayerBehavior layerBehavior = layerRange != null ? PasteLayerBehavior.RENDERED_ONLY : PasteLayerBehavior.ALL;

		ServerChunkLoader chunkLoader = this.chunkWalkForceLoadChunks.getValue()
		                              ? new ServerChunkLoader(level,
		                                                      this.chunkWalkGenerateMissingChunks.getValue(),
		                                                      this.chunkWalkMaxLoadsPerTick.getValue())
		                              : null;

		TaskMaterialListPlacement task = new TaskMaterialListPlacement(
				ctx, Collections.singletonList(placement), layerRange, layerBehavior, result, chunkLoader,
				ignoreState, countEntities, countContainers,
				this.chunkWalkPauseMsptThreshold.getValue(), null);

		task.setOnProgress(() -> this.sendMaterialListStatus(session));

		task.setOnComplete(() ->
		                   {
			                   // A cancel already moved the session out of RUNNING
			                   if (session.isRunning())
			                   {
				                   session.setState(ServerTaskSession.State.DONE);
			                   }

			                   session.touch();

			                   if (onComplete != null)
			                   {
				                   onComplete.accept(session);
			                   }
		                   });

		session.setTask(task);
		TaskScheduler.getInstance().scheduleTask(task, 1);

		Servux.debugLog("litematic_data: started material list session {} for placement '{}'", session.getSessionId(), placement.getName());

		return session;
	}

	/**
	 * Handles a {@code LitematicaMaterials} request: a client asking the server to price up
	 * a placement it uploaded.
	 * <p>
	 * Same shape as {@link #handleClientVerifyRequest} - the schematic travels with the
	 * placement - because the two answer the same question about the same blocks; a
	 * verification says what is wrong, a material list says what is still needed. Clients
	 * learn that this exists from the {@code materials} entry in the {@code Features}
	 * metadata list, not from the protocol version.
	 */
	public void handleClientMaterialListRequest(ServerPlayer player, CompoundData tags)
	{
		if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
		{
			return;
		}

		int[] sessionIdArray = tags.getIntArray("SessionId");

		if (!this.hasPermissionsForMaterials(player))
		{
			Servux.debugLog("litematic_data: Denying Litematic Material List for player {}, Insufficient Permissions.", player.getName().tryCollapseToString());
			this.sendTaskError(player, ServerTaskKind.MATERIALS, sessionIdArray, "servux.litematics.error.insufficent_for_materials");
			return;
		}

		SchematicPlacement placement;

		try
		{
			// createFromData() rethrows parse failures as unchecked, and this runs off a
			// packet, so a malformed payload must not escape into the network thread
			placement = SchematicPlacement.createFromData(tags);
		}
		catch (Exception e)
		{
			Servux.LOGGER.warn("litematic_data: failed to read the material list placement from player {}; {}", player.getName().tryCollapseToString(), e.getLocalizedMessage());
			placement = null;
		}

		if (placement == null)
		{
			this.sendTaskError(player, ServerTaskKind.MATERIALS, sessionIdArray, "servux.litematics.materials.error.bad_placement");
			return;
		}

		LayerRange layerRange = tags.getCodec("RenderLayerRange", LayerRange.CODEC).orElse(null);

		// The client picks the session id so that it can match replies to its own request
		UUID sessionId = VerifyResultSerializer.uuidFromIntArray(sessionIdArray);

		MaterialListSession session = this.startMaterialList(player.level(), placement, layerRange,
		                                                     tags.getBoolean("IgnoreState"),
		                                                     tags.getBoolean("CountEntities"),
		                                                     tags.getBoolean("CountContainers"),
		                                                     player.getUUID(), null, sessionId,
		                                                     this::beginMaterialListStreaming);

		if (session == null)
		{
			this.sendTaskError(player, ServerTaskKind.MATERIALS, sessionIdArray, "servux.litematics.materials.error.already_running");
		}
	}

	/** True when the area covers more blocks than one analysis is allowed to read. */
	public boolean exceedsAnalyzeVolume(AreaSelection area)
	{
		return this.exceedsVolume(area, this.analyzeMaxVolume.getValue());
	}

	private boolean exceedsVolume(AreaSelection area, final int max)
	{
		if (max <= 0)
		{
			return false;
		}

		long volume = 0;

		for (Box box : area.getAllSubRegionBoxes())
		{
			BlockPos pos1 = box.getPos1();
			BlockPos pos2 = box.getPos2();

			if (pos1 == null || pos2 == null)
			{
				continue;
			}

			// Corners are inclusive on both ends, hence the +1 on each axis
			long sizeX = Math.abs(pos1.getX() - pos2.getX()) + 1L;
			long sizeY = Math.abs(pos1.getY() - pos2.getY()) + 1L;
			long sizeZ = Math.abs(pos1.getZ() - pos2.getZ()) + 1L;

			volume += sizeX * sizeY * sizeZ;

			if (volume > max)
			{
				return true;
			}
		}

		return false;
	}

	@Override
	public boolean hasPermission(ServerPlayer player)
	{
		return PermissionsUtil.check(player, this.permNode, this.permissionLevel.getValue());
	}

	public boolean hasPermissionsForPaste(ServerPlayer player)
	{
		return this.hasPermission(player) && PermissionsUtil.check(player, this.permNode + ".paste", this.pastePermissionLevel.getValue());
	}

	public boolean hasPermissionsForTask(ServerPlayer player, String task)
	{
		return this.hasPermission(player) && PermissionsUtil.check(player, this.permNode + ".task." + task, this.taskPermissionLevel.getValue());
	}

	public boolean hasPermissionsForVerify(ServerPlayer player)
	{
		return this.hasPermission(player) && PermissionsUtil.check(player, this.permNode + ".verify", this.verifyPermissionLevel.getValue());
	}

	public boolean hasPermissionsForAnalyze(ServerPlayer player)
	{
		return this.hasPermission(player) && PermissionsUtil.check(player, this.permNode + ".analyze", this.analyzePermissionLevel.getValue());
	}

	public boolean hasPermissionsForMaterials(ServerPlayer player)
	{
		return this.hasPermission(player) && PermissionsUtil.check(player, this.permNode + ".materials", this.materialsPermissionLevel.getValue());
	}

	public boolean isSyncmaticaInteropEnabled()
	{
		return this.verifySyncmaticaInterop.getValue();
	}

	public boolean shouldSendPlayerTaskFeedback()
	{
		return this.playerTaskFeedback.getValue();
	}

	public boolean shouldDeDuplicateEntities()
	{
		return this.deDuplicateSchematicEntities.getValue();
	}
}
