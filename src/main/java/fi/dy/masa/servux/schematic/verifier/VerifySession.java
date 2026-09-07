package fi.dy.masa.servux.schematic.verifier;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.scheduler.session.ServerTaskSession;

/**
 * One in-flight (or just finished) verification.
 * <p>
 * Everything about session lifecycle, batch streaming and talking back to the requester
 * lives in {@link ServerTaskSession}; all this adds is the {@link VerifyResult} being
 * accumulated.
 */
public class VerifySession extends ServerTaskSession
{
	private final VerifyResult result;

	public VerifySession(UUID sessionId, UUID owner, String placementName, ServerLevel level,
	                     VerifyResult result, @Nullable CommandSourceStack source)
	{
		super(sessionId, owner, ServerTaskKind.VERIFY, placementName, level, source);

		this.result = result;
	}

	public VerifyResult getResult()
	{
		return this.result;
	}

	@Override
	public ChunkWalkProgress getProgress()
	{
		return this.result.getProgress();
	}

	/** The placement being verified; the reports name it this way. */
	public String getPlacementName()
	{
		return this.getSubject();
	}
}
