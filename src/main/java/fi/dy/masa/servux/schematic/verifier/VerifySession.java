package fi.dy.masa.servux.schematic.verifier;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
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
		RUNNING,
		DONE,
		CANCELLED,
		FAILED
	}

	private final UUID sessionId;
	/** The requesting player's UUID, or {@link #CONSOLE_OWNER} for a non-player source. */
	private final UUID owner;
	private final String placementName;
	private final String dimension;
	private final VerifyResult result;
	private final long startTime;

	@Nullable private final CommandSourceStack source;
	@Nullable private TaskVerifySchematicPerChunk task;

	private State state = State.RUNNING;
	private long lastActivity;

	public static final UUID CONSOLE_OWNER = new UUID(0L, 0L);

	public VerifySession(UUID sessionId, UUID owner, String placementName, String dimension,
	                     VerifyResult result, @Nullable CommandSourceStack source)
	{
		this.sessionId = sessionId;
		this.owner = owner;
		this.placementName = placementName;
		this.dimension = dimension;
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
		if (this.source == null || this.owner.equals(CONSOLE_OWNER))
		{
			return null;
		}

		return this.source.getServer().getPlayerList().getPlayer(this.owner);
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
