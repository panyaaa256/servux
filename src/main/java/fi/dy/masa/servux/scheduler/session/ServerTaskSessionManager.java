package fi.dy.masa.servux.scheduler.session;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;

import fi.dy.masa.servux.Servux;

/**
 * Tracks server side task sessions, one active session per requester <i>per kind</i>.
 * <p>
 * Per kind rather than per requester outright: verifying a build and analysing an area are
 * both read-only walks that answer different questions, and a player who wants both should
 * not have to serialise them by hand. Two runs of the <i>same</i> kind are still refused,
 * because that is almost always a double click rather than an intent.
 * <p>
 * Expiry is swept lazily on every registration rather than from a tick hook. The timeout
 * exists to bound sessions waiting on a client that went away mid-stream.
 */
public class ServerTaskSessionManager
{
	public static final ServerTaskSessionManager INSTANCE = new ServerTaskSessionManager();

	private final Map<UUID, ServerTaskSession> sessions = new ConcurrentHashMap<>();

	private ServerTaskSessionManager() {}

	/**
	 * Registers a session, unless the owner already has one of the same kind running.
	 *
	 * @return false if the owner already has an active session of that kind
	 */
	public boolean add(ServerTaskSession session, int timeoutSeconds)
	{
		this.sweep(timeoutSeconds);

		if (this.getRunningFor(session.getOwner(), session.getKind()) != null)
		{
			return false;
		}

		this.sessions.put(session.getSessionId(), session);

		return true;
	}

	@Nullable
	public ServerTaskSession get(UUID sessionId)
	{
		return this.sessions.get(sessionId);
	}

	/**
	 * Looks a session up and checks it is of the expected type, so that a reply naming
	 * someone else's session of a different kind cannot be coerced into this one.
	 */
	@Nullable
	public <T extends ServerTaskSession> T get(UUID sessionId, Class<T> type)
	{
		ServerTaskSession session = this.sessions.get(sessionId);

		return type.isInstance(session) ? type.cast(session) : null;
	}

	/** The owner's active session of the given kind, or null. */
	@Nullable
	public ServerTaskSession getRunningFor(UUID owner, ServerTaskKind kind)
	{
		for (ServerTaskSession session : this.sessions.values())
		{
			if (session.getOwner().equals(owner) && session.getKind() == kind && session.isActive())
			{
				return session;
			}
		}

		return null;
	}

	/** The owner's most recent session of the given kind, running or not. */
	@Nullable
	public ServerTaskSession getLatestFor(UUID owner, ServerTaskKind kind)
	{
		ServerTaskSession latest = null;

		for (ServerTaskSession session : this.sessions.values())
		{
			if (session.getOwner().equals(owner) && session.getKind() == kind &&
				(latest == null || session.getStartTime() > latest.getStartTime()))
			{
				latest = session;
			}
		}

		return latest;
	}

	public Collection<ServerTaskSession> getAll()
	{
		return this.sessions.values();
	}

	/** Every active session, or only those of one kind when {@code kind} is given. */
	public List<ServerTaskSession> getAllRunning(@Nullable ServerTaskKind kind)
	{
		List<ServerTaskSession> list = new ArrayList<>();

		for (ServerTaskSession session : this.sessions.values())
		{
			if (session.isActive() && (kind == null || session.getKind() == kind))
			{
				list.add(session);
			}
		}

		return list;
	}

	public void remove(UUID sessionId)
	{
		this.sessions.remove(sessionId);
	}

	/**
	 * Drops sessions that have been idle for longer than the timeout. Running sessions are
	 * cancelled first so their task stops too.
	 */
	public void sweep(int timeoutSeconds)
	{
		if (timeoutSeconds <= 0)
		{
			return;
		}

		final long cutoff = System.currentTimeMillis() - (timeoutSeconds * 1000L);
		Iterator<Map.Entry<UUID, ServerTaskSession>> iterator = this.sessions.entrySet().iterator();

		while (iterator.hasNext())
		{
			ServerTaskSession session = iterator.next().getValue();

			if (session.getLastActivity() < cutoff)
			{
				if (session.isActive())
				{
					Servux.debugLog("ServerTaskSessionManager: expiring stale {} session {}", session.getKind().getName(), session.getSessionId());
					session.cancel();
				}

				iterator.remove();
			}
		}
	}

	public void clear()
	{
		for (ServerTaskSession session : this.sessions.values())
		{
			if (session.isActive())
			{
				session.cancel();
			}
		}

		this.sessions.clear();
	}
}
