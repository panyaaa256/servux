package fi.dy.masa.servux.scheduler.session;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;
import fi.dy.masa.servux.scheduler.tasks.TaskChunkWalkerBase;

/**
 * One in-flight (or just finished) server side task, and the thread that talks back to
 * whoever asked for it.
 * <p>
 * A session outlives its task: the walk finishes, then the result is handed over in
 * acknowledged batches, and only once the last batch is acknowledged is the session done.
 * That is why the state machine has a {@link State#STREAMING} step between running and
 * done, and why the batch cursor lives here rather than on the task.
 * <p>
 * The requester is either a command source or a registered player; {@link #sendMessage}
 * hides which.
 */
public abstract class ServerTaskSession
{
	public enum State
	{
		/** The task is still walking chunks. */
		RUNNING,
		/** The walk finished; result batches are being handed to the client. */
		STREAMING,
		DONE,
		CANCELLED,
		FAILED
	}

	/** Owner value standing in for a non-player source, i.e. the server console. */
	public static final UUID CONSOLE_OWNER = new UUID(0L, 0L);

	private final UUID sessionId;
	/** The requesting player's UUID, or {@link #CONSOLE_OWNER} for a non-player source. */
	private final UUID owner;
	private final ServerTaskKind kind;
	/** What the task is operating on - a placement or an area name - for reporting. */
	private final String subject;
	private final String dimension;
	/** Kept so the session can reach the player list without a global server lookup. */
	private final ServerLevel level;
	private final long startTime;

	@Nullable private final CommandSourceStack source;
	@Nullable private TaskChunkWalkerBase task;

	/** Set once the walk finishes and the result starts being streamed out. */
	@Nullable private IResultBatcher batcher;
	/** The batch the client has acknowledged; -1 means none sent yet. */
	private int acknowledgedBatch = -1;

	private State state = State.RUNNING;
	private long lastActivity;

	protected ServerTaskSession(UUID sessionId, UUID owner, ServerTaskKind kind, String subject,
	                            ServerLevel level, @Nullable CommandSourceStack source)
	{
		this.sessionId = sessionId;
		this.owner = owner;
		this.kind = kind;
		this.subject = subject;
		this.level = level;
		this.dimension = level.dimension().identifier().toString();
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

	public ServerTaskKind getKind()
	{
		return this.kind;
	}

	public String getSubject()
	{
		return this.subject;
	}

	public String getDimension()
	{
		return this.dimension;
	}

	public ServerLevel getLevel()
	{
		return this.level;
	}

	/** The chunk counters of the run, for progress reporting. */
	public abstract ChunkWalkProgress getProgress();

	/** The requesting player, if they are still online. */
	@Nullable
	public ServerPlayer getPlayer()
	{
		return this.owner.equals(CONSOLE_OWNER) ? null : this.level.getServer().getPlayerList().getPlayer(this.owner);
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
	public IResultBatcher getBatcher()
	{
		return this.batcher;
	}

	public void setBatcher(@Nullable IResultBatcher batcher)
	{
		this.batcher = batcher;
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
	public TaskChunkWalkerBase getTask()
	{
		return this.task;
	}

	public void setTask(@Nullable TaskChunkWalkerBase task)
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

		ServerPlayer player = this.getPlayer();

		if (player != null)
		{
			player.sendSystemMessage(message, false);
		}
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
