package fi.dy.masa.servux.schematic.verifier;

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
 * Tracks verification sessions, one per requester.
 * <p>
 * Expiry is swept lazily on every access rather than from a tick hook: command driven
 * sessions end when their task ends, so there is nothing to reap on a timer yet. The
 * timeout exists to bound sessions that are waiting on a client that went away, which is
 * what the ACK-pull result stream will need.
 */
public class VerifySessionManager
{
	public static final VerifySessionManager INSTANCE = new VerifySessionManager();

	private final Map<UUID, VerifySession> sessions = new ConcurrentHashMap<>();

	private VerifySessionManager() {}

	/**
	 * Registers a session, unless the owner already has one running.
	 *
	 * @return false if the owner already has a running session
	 */
	public boolean add(VerifySession session, int timeoutSeconds)
	{
		this.sweep(timeoutSeconds);

		if (this.getRunningFor(session.getOwner()) != null)
		{
			return false;
		}

		this.sessions.put(session.getSessionId(), session);

		return true;
	}

	@Nullable
	public VerifySession get(UUID sessionId)
	{
		return this.sessions.get(sessionId);
	}

	/** The owner's running session, or null. */
	@Nullable
	public VerifySession getRunningFor(UUID owner)
	{
		for (VerifySession session : this.sessions.values())
		{
			if (session.getOwner().equals(owner) && session.isRunning())
			{
				return session;
			}
		}

		return null;
	}

	/** The owner's most recent session, running or not. */
	@Nullable
	public VerifySession getLatestFor(UUID owner)
	{
		VerifySession latest = null;

		for (VerifySession session : this.sessions.values())
		{
			if (session.getOwner().equals(owner) &&
				(latest == null || session.getStartTime() > latest.getStartTime()))
			{
				latest = session;
			}
		}

		return latest;
	}

	public Collection<VerifySession> getAll()
	{
		return this.sessions.values();
	}

	public List<VerifySession> getAllRunning()
	{
		List<VerifySession> list = new ArrayList<>();

		for (VerifySession session : this.sessions.values())
		{
			if (session.isRunning())
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
		Iterator<Map.Entry<UUID, VerifySession>> iterator = this.sessions.entrySet().iterator();

		while (iterator.hasNext())
		{
			VerifySession session = iterator.next().getValue();

			if (session.getLastActivity() < cutoff)
			{
				if (session.isRunning())
				{
					Servux.debugLog("VerifySessionManager: expiring stale session {}", session.getSessionId());
					session.cancel();
				}

				iterator.remove();
			}
		}
	}

	public void clear()
	{
		for (VerifySession session : this.sessions.values())
		{
			if (session.isRunning())
			{
				session.cancel();
			}
		}

		this.sessions.clear();
	}
}
