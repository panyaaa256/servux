package fi.dy.masa.servux.schematic.verifier;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import fi.dy.masa.servux.scheduler.tasks.TaskVerifySchematicPerChunk;

/**
 * One in-flight (or just finished) verification.
 * <p>
 * A session owns the {@link VerifyResult} and the task producing it, and knows how to talk
 * back to whoever asked for it - either a command source or, once the packet route lands,
 * a registered player.
 */
public class VerifySession
{
	public enum State
	{
		/** The verify task is still walking chunks. */
		RUNNING,
		/** Verification finished; result batches are being handed to the client. */
		STREAMING,
		DONE,
		CANCELLED,
		FAILED
	}

	private final UUID sessionId;
	/** The requesting player's UUID, or {@link #CONSOLE_OWNER} for a non-player source. */
	private final UUID owner;
	private final String placementName;
	private final String dimension;
	/** Kept so the session can reach the player list without a global server lookup. */
	private final ServerLevel level;
	private final VerifyResult result;
	private final long startTime;

	@Nullable private final CommandSourceStack source;
	@Nullable private TaskVerifySchematicPerChunk task;

	/** Set once verification finishes and the result starts being streamed out. */
	@Nullable private VerifyResultSerializer serializer;
	/** The batch the client has acknowledged; -1 means none sent yet. */
	private int acknowledgedBatch = -1;

	private State state = State.RUNNING;
	private long lastActivity;

	public static final UUID CONSOLE_OWNER = new UUID(0L, 0L);

	public VerifySession(UUID sessionId, UUID owner, String placementName, ServerLevel level,
	                     VerifyResult result, @Nullable CommandSourceStack source)
	{
		this.sessionId = sessionId;
		this.owner = owner;
		this.placementName = placementName;
		this.level = level;
		this.dimension = level.dimension().identifier().toString();
		this.result = result;
		this.source = source;
		this.startTime = System.currentTimeMillis();
		this.lastActivity = this.startTime;
	}

	public UUID getSessionId()
	{
		return this.sessionId;
	}

	public UUID getOwner()
	{
		return this.owner;
	}

	public String getPlacementName()
	{
		return this.placementName;
	}

	public String getDimension()
	{
		return this.dimension;
	}

	public ServerLevel getLevel()
	{
		return this.level;
	}

	/** The requesting player, if they are still online. */
	@Nullable
	public ServerPlayer getPlayer()
	{
		return this.owner.equals(CONSOLE_OWNER) ? null : this.level.getServer().getPlayerList().getPlayer(this.owner);
	}

	public VerifyResult getResult()
	{
		return this.result;
	}

	public State getState()
	{
		return this.state;
	}

	public void setState(State state)
	{
		this.state = state;
		this.touch();
	}

	public long getStartTime()
	{
		return this.startTime;
	}

	public long getElapsedTime()
	{
		return System.currentTimeMillis() - this.startTime;
	}

	public long getLastActivity()
	{
		return this.lastActivity;
	}

	public void touch()
	{
		this.lastActivity = System.currentTimeMillis();
	}

	public boolean isRunning()
	{
		return this.state == State.RUNNING;
	}

	/** True while the session is still doing something the client should wait for. */
	public boolean isActive()
	{
		return this.state == State.RUNNING || this.state == State.STREAMING;
	}

	@Nullable
	public VerifyResultSerializer getSerializer()
	{
		return this.serializer;
	}

	public void setSerializer(@Nullable VerifyResultSerializer serializer)
	{
		this.serializer = serializer;
	}

	public int getAcknowledgedBatch()
	{
		return this.acknowledgedBatch;
	}

	public void setAcknowledgedBatch(int batch)
	{
		this.acknowledgedBatch = batch;
		this.touch();
	}

	@Nullable
	public TaskVerifySchematicPerChunk getTask()
	{
		return this.task;
	}

	public void setTask(@Nullable TaskVerifySchematicPerChunk task)
	{
		this.task = task;
	}

	@Nullable
	public CommandSourceStack getSource()
	{
		return this.source;
	}

	/** Sends a line back to whoever started this session, if they are still reachable. */
	public void sendMessage(Component message)
	{
		if (this.source != null)
		{
			this.source.sendSuccess(() -> message, false);
			return;
		}

		ServerPlayer player = this.getOwnerPlayer();

		if (player != null)
		{
			player.sendSystemMessage(message, false);
		}
	}

	@Nullable
	private ServerPlayer getOwnerPlayer()
	{
		return this.getPlayer();
	}

	public void cancel()
	{
		if (this.task != null)
		{
			this.task.cancel();
		}

		this.setState(State.CANCELLED);
	}
}
