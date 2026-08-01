package fi.dy.masa.servux.dataproviders;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import me.lucko.fabric.api.permissions.v0.Permissions;
import org.apache.commons.lang3.tuple.Pair;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

import fi.dy.masa.servux.Reference;
import fi.dy.masa.servux.Servux;
import fi.dy.masa.servux.network.IPluginServerPlayHandler;
import fi.dy.masa.servux.network.ServerPlayHandler;
import fi.dy.masa.servux.network.packet.ServuxLitematicaHandler;
import fi.dy.masa.servux.network.packet.ServuxLitematicaPacket;
import fi.dy.masa.servux.scheduler.TaskContext;
import fi.dy.masa.servux.scheduler.TaskScheduler;
import fi.dy.masa.servux.scheduler.tasks.TaskVerifySchematicPerChunk;
import fi.dy.masa.servux.schematic.LitematicaSchematic;
import fi.dy.masa.servux.schematic.placement.SchematicPlacement;
import fi.dy.masa.servux.schematic.transmit.SchematicBufferManager;
import fi.dy.masa.servux.schematic.verifier.VerifyChunkLoader;
import fi.dy.masa.servux.schematic.verifier.VerifyNbtComparator;
import fi.dy.masa.servux.schematic.verifier.VerifyReport;
import fi.dy.masa.servux.schematic.verifier.VerifyResult;
import fi.dy.masa.servux.schematic.verifier.VerifyResultSerializer;
import fi.dy.masa.servux.schematic.verifier.VerifySession;
import fi.dy.masa.servux.schematic.verifier.VerifySessionManager;
import fi.dy.masa.servux.settings.IServuxSetting;
import fi.dy.masa.servux.settings.ServuxBoolSetting;
import fi.dy.masa.servux.settings.ServuxIntSetting;
import fi.dy.masa.servux.util.*;
import fi.dy.masa.servux.util.nbt.NbtUtils;
import fi.dy.masa.servux.util.nbt.NbtView;
import fi.dy.masa.servux.util.position.PositionUtils;

public class LitematicsDataProvider extends DataProviderBase
{
    public static final LitematicsDataProvider INSTANCE = new LitematicsDataProvider();
	private final static ServuxLitematicaHandler<ServuxLitematicaPacket.Payload> HANDLER = ServuxLitematicaHandler.getInstance();
	private final CompoundTag metadata = new CompoundTag();
	private final ServuxIntSetting permissionLevel = new ServuxIntSetting(this, "permission_level", 0, 4, 0);
    private final ServuxIntSetting pastePermissionLevel = new ServuxIntSetting(this, "permission_level_paste", 0, 4, 0);
    private final ServuxIntSetting verifyPermissionLevel = new ServuxIntSetting(this, "permission_level_verify", 0, 4, 0);
    public ServuxBoolSetting fixRaiLRotations = new ServuxBoolSetting(this, "fix_rail_rotations", true);
    public ServuxBoolSetting fixStairMirror = new ServuxBoolSetting(this, "fix_stairs_mirror", true);
    public ServuxBoolSetting fixChestMirror = new ServuxBoolSetting(this, "fix_chest_mirror", true);
    public final ServuxIntSetting verifyMaxResultPositions = new ServuxIntSetting(this, "verify_max_result_positions", 200000, 10000000, 0);
    public final ServuxIntSetting verifyBatchPositions = new ServuxIntSetting(this, "verify_batch_positions", 16384, 262144, 256);
    public final ServuxIntSetting verifySessionTimeout = new ServuxIntSetting(this, "verify_session_timeout", 300, 86400, 0);
    public final ServuxBoolSetting verifySyncmaticaInterop = new ServuxBoolSetting(this, "verify_syncmatica_interop", true);
    public final ServuxBoolSetting verifyForceLoadChunks = new ServuxBoolSetting(this, "verify_force_load_chunks", true);
    public final ServuxBoolSetting verifyGenerateMissingChunks = new ServuxBoolSetting(this, "verify_generate_missing_chunks", false);
    public final ServuxIntSetting verifyMaxChunkLoadsPerTick = new ServuxIntSetting(this, "verify_max_chunk_loads_per_tick", 2, 16, 1);
    public final ServuxIntSetting verifyPauseMsptThreshold = new ServuxIntSetting(this, "verify_pause_mspt_threshold", 45, 1000, 0);
    public final ServuxBoolSetting verifyNbt = new ServuxBoolSetting(this, "verify_nbt", true);
    public final ServuxBoolSetting verifyNbtSlotExact = new ServuxBoolSetting(this, "verify_nbt_slot_exact", false);
    public final ServuxBoolSetting verifyNbtStrict = new ServuxBoolSetting(this, "verify_nbt_strict", false);
    private final List<IServuxSetting<?>> settings = List.of(this.permissionLevel, this.pastePermissionLevel, this.verifyPermissionLevel,
                                                             this.fixRaiLRotations, this.fixStairMirror, this.fixChestMirror,
                                                             this.verifyMaxResultPositions, this.verifyBatchPositions, this.verifySessionTimeout, this.verifySyncmaticaInterop,
                                                             this.verifyForceLoadChunks, this.verifyGenerateMissingChunks,
                                                             this.verifyMaxChunkLoadsPerTick, this.verifyPauseMsptThreshold,
                                                             this.verifyNbt, this.verifyNbtSlotExact, this.verifyNbtStrict);

    private final List<UUID> registeredPlayers = new ArrayList<>();
    private final List<UUID> invalidPlayers = new ArrayList<>();
    private final SchematicBufferManager bufferManager = new SchematicBufferManager();
    private final Path transmitDir;

    protected LitematicsDataProvider()
    {
        super("litematic_data",
                ServuxLitematicaHandler.CHANNEL_ID,
                ServuxLitematicaPacket.PROTOCOL_VERSION,
                0, Reference.MOD_ID+ ".provider.litematic_data",
                "Litematics Data provider.");

        this.metadata.putString("name", this.getName());
        this.metadata.putString("id", this.getNetworkChannel().toString());
        this.metadata.putInt("version", this.getProtocolVersion());
        this.metadata.putString("servux", Reference.MOD_STRING);

        // Capability advertisement, deliberately separate from PROTOCOL_VERSION: bumping the
        // version would lock out every existing Litematica client, whereas clients that do not
        // know this key simply ignore it. Clients branch on the capability, not the version.
        ListTag features = new ListTag();
        features.add(StringTag.valueOf("verify"));
        features.add(StringTag.valueOf("verify_nbt"));
        this.metadata.put("Features", features);

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
        Path dir = this.transmitDir != null ? this.transmitDir : Reference.DEFAULT_RUN_DIR.resolve("schematics").normalize();

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

    public void registerPlayer(ServerPlayer player)
    {
        if (!this.isEnabled()) return;

        if (!this.hasPermission(player))
        {
            // No Permission
            Servux.debugLog("litematic_data: Denying access for player {}, Insufficient Permissions", player.getName().tryCollapseToString());
            return;
        }

        Servux.debugLog("litematic_data: sendMetadata to player {}", player.getName().tryCollapseToString());

        this.registeredPlayers.add(player.getUUID());

        // Sends Metadata handshake, it doesn't succeed the first time, so using networkHandler
        if (player.connection != null)
        {
            HANDLER.sendPlayPayload(player.connection, new ServuxLitematicaPacket.Payload(ServuxLitematicaPacket.MetadataResponse(this.metadata)));
        }
        else
        {
            HANDLER.sendPlayPayload(player, new ServuxLitematicaPacket.Payload(ServuxLitematicaPacket.MetadataResponse(this.metadata)));
        }
    }

    public void onPacketFailure(ServerPlayer player)
    {
        this.setPlayerInvalid(player);
        this.registeredPlayers.remove(player.getUUID());
    }

    public void removePlayer(ServerPlayer player)
    {
        this.removeInvalidPlayer(player);
        this.registeredPlayers.remove(player.getUUID());
    }

    private void setPlayerInvalid(ServerPlayer player)
    {
        if (!this.invalidPlayers.contains(player.getUUID()))
        {
            this.invalidPlayers.add(player.getUUID());
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

    public void onBlockEntityRequest(ServerPlayer player, BlockPos pos)
    {
        if (!this.hasPermission(player) || !this.isPlayerRegistered(player) || !this.isEnabled())
        {
            return;
        }

        //Servux.logger.warn("LitematicsDataProvider#onBlockEntityRequest(): from player {}", player.getName().getLiteralString());

        BlockEntity be = player.level().getBlockEntity(pos);
        CompoundTag nbt = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();
        HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleBlockResponse(pos, nbt));
    }

    public void onEntityRequest(ServerPlayer player, int entityId)
    {
        if (!this.hasPermission(player) || !this.isPlayerRegistered(player) || !this.isEnabled())
        {
            return;
        }

        //Servux.logger.warn("LitematicsDataProvider#onEntityRequest(): from player {} // entityId [{}]", player.getName().getLiteralString(), entityId);
        Entity entity = player.level().getEntity(entityId);

        if (entity != null)
        {
            NbtView view = NbtView.getWriter(player.level().registryAccess());
            Identifier id = EntityType.getKey(entity.getType());

            entity.saveWithoutId(view.getWriter());
            CompoundTag nbt = view.readNbt();

            if (nbt != null && id != null)
            {
                nbt.putString("id", id.toString());
                HANDLER.encodeServerData(player, ServuxLitematicaPacket.SimpleEntityResponse(entityId, nbt));
            }
        }
    }

    public void onBulkEntityRequest(ServerPlayer player, ChunkPos chunkPos, CompoundTag req)
    {
        if (!this.hasPermission(player) || !this.isPlayerRegistered(player) || !this.isEnabled())
        {
            Servux.LOGGER.warn("litematic_data: Denying Litematic onBulkEntityRequest from player {}, Insufficient Permissions.", player.getName().getString());
            player.sendSystemMessage(StringUtils.translate("servux.litematics.error.bulk_request.insufficent"));
            return;
        }
        if (req == null || req.isEmpty())
        {
//            Servux.LOGGER.warn("litematic_data: Litematic onBulkEntityRequest from player {}, request is empty.", player.getName().getString());
            return;
        }

        ServerLevel world = player.level();
        LevelChunk chunk = world != null ? world.getChunkSource().getChunkNow(chunkPos.x, chunkPos.z) : null;

        if (chunk == null)
        {
            player.sendSystemMessage(StringUtils.translate("servux.litematics.error.bulk_request.chunk_not_loaded", chunkPos.toString()));
            return;
        }

        // TODO --> Split out the task this way (I should have done this under 0.3.0),
        //  So we need to check if the "Task" is not included for now... (Wait for the updates to bake in)
        if ((req.contains("Task") && req.getStringOr("Task", "").equals("BulkEntityRequest")) ||
            !req.contains("Task"))
        {
            Servux.debugLog("litematic_data: Sending Bulk NBT Data for ChunkPos {} to player {}", chunkPos.toString(), player.getName().tryCollapseToString());

            long timeStart = System.currentTimeMillis();
            ListTag tileList = new ListTag();
            ListTag entityList = new ListTag();
            int minY = req.getIntOr("minY", -64);
            int maxY = req.getIntOr("maxY", 319);
            BlockPos pos1 = new BlockPos(chunkPos.getMinBlockX(), minY, chunkPos.getMinBlockZ());
            BlockPos pos2 = new BlockPos(chunkPos.getMaxBlockX(), maxY, chunkPos.getMaxBlockZ());
            net.minecraft.world.phys.AABB bb = PositionUtils.createEnclosingAABB(pos1, pos2);
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
                CompoundTag beTag = be != null ? be.saveWithFullMetadata(player.registryAccess()) : new CompoundTag();
                tileList.add(beTag);
            }

            for (Entity entity : entities)
            {
                NbtView view = NbtView.getWriter(player.level().registryAccess());
                Identifier id = EntityType.getKey(entity.getType());

                entity.saveWithoutId(view.getWriter());
                CompoundTag entTag = view.readNbt();

                if (entTag != null && id != null)
                {
                    Vec3 posVec = new Vec3(entity.getX() - pos1.getX(), entity.getY() - pos1.getY(), entity.getZ() - pos1.getZ());
                    entTag.putString("id", id.toString());

                    NbtUtils.writeEntityPositionToTag(posVec, entTag);
                    entTag.putInt("entityId", entity.getId());
                    entityList.add(entTag);
                }
            }

            CompoundTag output = new CompoundTag();
            output.putString("Task", "BulkEntityReply");
            output.put("TileEntities", tileList);
            output.put("Entities", entityList);
            output.putInt("chunkX", chunkPos.x);
            output.putInt("chunkZ", chunkPos.z);
            long timeElapsed = System.currentTimeMillis() - timeStart;

            HANDLER.encodeServerData(player, ServuxLitematicaPacket.ResponseS2CStart(output));
            player.sendSystemMessage(
                    StringUtils.translate("servux.litematics.feedback.bulk_request.acknowledge",
                                          world.dimension().identifier().toString(), chunkPos.toString(),
                                          tileList.size(), entityList.size(),
                                          timeElapsed), false
            );
        }
    }

    public void handleClientPasteRequest(ServerPlayer player, int transactionId, CompoundTag tags)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled())
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

        if (tags.getStringOr("Task", "").equals("LitematicaPaste"))
        {
            Servux.debugLog("litematic_data: Servux Paste request from player {}", player.getName().tryCollapseToString());

            long timeStart = System.currentTimeMillis();
            SchematicPlacement placement = SchematicPlacement.createFromNbt(tags);
            ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
            PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
            LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);
            placement.pasteTo(player.level(), replaceMode, layerBehavior, layerRange);
            long timeElapsed = System.currentTimeMillis() - timeStart;
            //player.sendMessage(Text.of("Pasted §b"+placement.getName()+"§r to world §d"+player.getServerWorld().getRegistryKey().getValue().toString()+"§r in §a"+timeElapsed+"§rms."), false);
            player.displayClientMessage(StringUtils.translate("servux.litematics.success.pasted", placement.getName(), player.level().dimension().identifier().toString(), timeElapsed), false);
        }
    }

    public void handleClientPasteRequestPair(ServerPlayer player, int transactionId, Pair<LitematicaSchematic, CompoundTag> schemPair)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled())
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

        if (schemPair.getLeft() != null)
        {
            Servux.debugLog("litematic_data: Servux Paste (Pair) request from player {}", player.getName().tryCollapseToString());

            long timeStart = System.currentTimeMillis();
            CompoundTag tags = schemPair.getRight();
            SchematicPlacement placement = SchematicPlacement.createFromNbt(schemPair.getLeft(), tags);
            ReplaceBehavior replaceMode = ReplaceBehavior.fromStringStatic(tags.getStringOr("ReplaceMode", ReplaceBehavior.NONE.name()));
            PasteLayerBehavior layerBehavior = PasteLayerBehavior.fromStringStatic(tags.getStringOr("PasteLayerBehavior", PasteLayerBehavior.ALL.name()));
            LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);
            placement.pasteTo(player.level(), replaceMode, layerBehavior, layerRange);
            long timeElapsed = System.currentTimeMillis() - timeStart;
            //player.sendMessage(Text.of("Pasted §b"+placement.getName()+"§r to world §d"+player.getServerWorld().getRegistryKey().getValue().toString()+"§r in §a"+timeElapsed+"§rms."), false);
            player.displayClientMessage(StringUtils.translate("servux.litematics.success.pasted", placement.getName(), player.level().dimension().identifier().toString(), timeElapsed), false);
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
        return this.startVerify(level, placement, layerRange, owner, source, null, onComplete);
    }

    /**
     * @param sessionId the id to run under, or null to mint one. A packet driven request
     *                  supplies its own so it can match the replies to its request.
     */
    @Nullable
    public VerifySession startVerify(ServerLevel level,
                                     SchematicPlacement placement,
                                     @Nullable LayerRange layerRange,
                                     UUID owner,
                                     @Nullable CommandSourceStack source,
                                     @Nullable UUID sessionId,
                                     @Nullable Consumer<VerifySession> onComplete)
    {
        VerifyResult result = new VerifyResult(this.verifyMaxResultPositions.getValue());
        VerifySession session = new VerifySession(sessionId != null ? sessionId : UUID.randomUUID(),
                                                  owner, placement.getName(), level, result, source);

        if (!VerifySessionManager.INSTANCE.add(session, this.verifySessionTimeout.getValue()))
        {
            return null;
        }

        ServerPlayer player = source != null ? source.getPlayer() : null;
        TaskContext ctx = new TaskContext(level.getServer(), level, player, placement.getName(), System.currentTimeMillis());

        // A layer range only takes effect when the behavior asks for it; see shouldPasteBlock()
        PasteLayerBehavior layerBehavior = layerRange != null ? PasteLayerBehavior.RENDERED_ONLY : PasteLayerBehavior.ALL;

        VerifyChunkLoader chunkLoader = this.verifyForceLoadChunks.getValue()
                                      ? new VerifyChunkLoader(level,
                                                              this.verifyGenerateMissingChunks.getValue(),
                                                              this.verifyMaxChunkLoadsPerTick.getValue())
                                      : null;

        VerifyNbtComparator nbtComparator = this.verifyNbt.getValue()
                                          ? new VerifyNbtComparator(this.verifyNbtSlotExact.getValue(),
                                                                    this.verifyNbtStrict.getValue())
                                          : null;

        TaskVerifySchematicPerChunk task = new TaskVerifySchematicPerChunk(
                ctx, Collections.singletonList(placement), layerRange, layerBehavior, result,
                chunkLoader, nbtComparator, this.verifyPauseMsptThreshold.getValue(), null);

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
    public void handleClientVerifyRequest(ServerPlayer player, int transactionId, CompoundTag tags)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
        {
            return;
        }

        if (!this.hasPermissionsForVerify(player))
        {
            Servux.debugLog("litematic_data: Denying Litematic Verify for player {}, Insufficient Permissions.", player.getName().tryCollapseToString());
            this.sendVerifyError(player, tags.getIntArray("SessionId").orElse(null), "servux.litematics.error.insufficent_for_verify");
            return;
        }

        SchematicPlacement placement;

        try
        {
            // createFromNbt() rethrows parse failures as unchecked, and this runs off a
            // packet, so a malformed payload must not escape into the network thread
            placement = SchematicPlacement.createFromNbt(tags);
        }
        catch (Exception e)
        {
            Servux.LOGGER.warn("litematic_data: failed to read the verify placement from player {}; {}", player.getName().tryCollapseToString(), e.getLocalizedMessage());
            placement = null;
        }

        int[] sessionIdArray = tags.getIntArray("SessionId").orElse(null);

        if (placement == null)
        {
            this.sendVerifyError(player, sessionIdArray, "servux.litematics.verify.error.bad_placement");
            return;
        }

        LayerRange layerRange = tags.read("RenderLayerRange", LayerRange.CODEC).orElse(null);

        // The client picks the session id so that it can match replies to its own request
        UUID sessionId = VerifyResultSerializer.uuidFromIntArray(sessionIdArray);

        VerifySession session = this.startVerify(player.level(), placement, layerRange, player.getUUID(), null,
                                                 sessionId, this::beginStreaming);

        if (session == null)
        {
            this.sendVerifyError(player, sessionIdArray, "servux.litematics.verify.error.already_running");
        }
    }

    /**
     * Handles the small C2S control messages that drive the result stream: the batch
     * acknowledgements that pull the next batch out of the server.
     */
    public void onTaskRequest(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
        {
            return;
        }

        if (!this.hasPermissionsForVerify(player))
        {
            return;
        }

        if (!tags.getStringOr("Task", "").equals("LitematicaVerifyAck"))
        {
            return;
        }

        VerifySession session = this.getOwnedSession(player, tags);

        if (session == null)
        {
            return;
        }

        session.setAcknowledgedBatch(tags.getIntOr("Batch", -1));
        this.sendNextVerifyBatch(player, session);
    }

    /** Handles a client asking to abandon its verification. */
    public void onTaskCancel(ServerPlayer player, CompoundTag tags)
    {
        if (!this.isPlayerRegistered(player) || !this.isEnabled() || tags == null || tags.isEmpty())
        {
            return;
        }

        if (!tags.getStringOr("Task", "").equals("LitematicaVerifyCancel"))
        {
            return;
        }

        VerifySession session = this.getOwnedSession(player, tags);

        if (session != null)
        {
            Servux.debugLog("litematic_data: verify session {} cancelled by the client", session.getSessionId());
            session.cancel();
            VerifySessionManager.INSTANCE.remove(session.getSessionId());
        }
    }

    /**
     * Looks a session up and checks it belongs to the asking player, so that one client
     * cannot drive or cancel another's verification.
     */
    @Nullable
    private VerifySession getOwnedSession(ServerPlayer player, CompoundTag tags)
    {
        UUID sessionId = VerifyResultSerializer.uuidFromIntArray(tags.getIntArray("SessionId").orElse(null));

        if (sessionId == null)
        {
            return null;
        }

        VerifySession session = VerifySessionManager.INSTANCE.get(sessionId);

        return session != null && session.getOwner().equals(player.getUUID()) ? session : null;
    }

    /** Called when verification finishes: switches the session over to handing out batches. */
    private void beginStreaming(VerifySession session)
    {
        ServerPlayer player = session.getPlayer();

        if (player == null)
        {
            // The requester left; nothing to stream to
            VerifySessionManager.INSTANCE.remove(session.getSessionId());
            return;
        }

        if (session.getState() == VerifySession.State.CANCELLED)
        {
            VerifySessionManager.INSTANCE.remove(session.getSessionId());
            return;
        }

        session.setSerializer(new VerifyResultSerializer(session.getResult()));
        session.setState(VerifySession.State.STREAMING);

        this.sendNextVerifyBatch(player, session);
    }

    /**
     * Sends one result batch. The client acknowledges it and that pulls the next one, so
     * a result of any size crosses the wire a bounded amount at a time and a client that
     * stops responding simply stops the flow (and is eventually reaped by the timeout).
     */
    private void sendNextVerifyBatch(ServerPlayer player, VerifySession session)
    {
        VerifyResultSerializer serializer = session.getSerializer();

        if (serializer == null || session.getState() != VerifySession.State.STREAMING)
        {
            return;
        }

        if (!serializer.hasMore() && serializer.getBatchNumber() > 0)
        {
            // The final batch has been acknowledged; the session has served its purpose
            session.setState(VerifySession.State.DONE);
            VerifySessionManager.INSTANCE.remove(session.getSessionId());
            return;
        }

        CompoundTag batch = serializer.nextBatch(this.verifyBatchPositions.getValue(), session.getSessionId());

        HANDLER.encodeServerData(player, ServuxLitematicaPacket.ResponseS2CStart(batch));
    }

    /** Progress ping, so the client's verifier GUI can show something while it waits. */
    public void sendVerifyStatus(VerifySession session)
    {
        ServerPlayer player = session.getPlayer();

        if (player == null)
        {
            return;
        }

        CompoundTag tag = new CompoundTag();
        tag.putString("Task", "LitematicaVerifyStatus");
        tag.putIntArray("SessionId", VerifyResultSerializer.uuidToIntArray(session.getSessionId()));
        tag.putInt("ChunksDone", session.getResult().getProcessedChunks());
        tag.putInt("ChunksTotal", session.getResult().getTotalChunks());
        tag.putInt("Mismatches", session.getResult().getTotalMismatches());

        HANDLER.encodeServerData(player, ServuxLitematicaPacket.TaskStatusSync(tag));
    }

    /** Reports a failure as a translation key, so the client renders it in its own language. */
    private void sendVerifyError(ServerPlayer player, @Nullable int[] sessionId, String key)
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("Task", "LitematicaVerifyError");

        if (sessionId != null)
        {
            tag.putIntArray("SessionId", sessionId);
        }

        tag.putString("Key", key);

        HANDLER.encodeServerData(player, ServuxLitematicaPacket.TaskResponse(tag));
    }


    @Override
    public boolean hasPermission(ServerPlayer player)
    {
        return Permissions.check(player, this.permNode, this.permissionLevel.getValue());
    }

	public boolean hasPermissionsForPaste(ServerPlayer player)
	{
		return this.hasPermission(player) && Permissions.check(player, this.permNode + ".paste", this.pastePermissionLevel.getValue());
	}

	public boolean hasPermissionsForVerify(ServerPlayer player)
	{
		return this.hasPermission(player) && Permissions.check(player, this.permNode + ".verify", this.verifyPermissionLevel.getValue());
	}

	public boolean isSyncmaticaInteropEnabled()
	{
		return this.verifySyncmaticaInterop.getValue();
	}

    @Override
    public void onTickEndPre()
    {
        // NO-OP
    }

    @Override
    public void onTickEndPost()
    {
        // NO-OP
    }
}
