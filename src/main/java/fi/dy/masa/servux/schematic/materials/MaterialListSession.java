package fi.dy.masa.servux.schematic.materials;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.scheduler.session.ServerTaskSession;

/** One in-flight (or just finished) material list run. */
public class MaterialListSession extends ServerTaskSession
{
	private final MaterialListResult result;

	public MaterialListSession(UUID sessionId, UUID owner, String placementName, ServerLevel level,
	                           MaterialListResult result, @Nullable CommandSourceStack source)
	{
		super(sessionId, owner, ServerTaskKind.MATERIALS, placementName, level, source);

		this.result = result;
	}

	public MaterialListResult getResult()
	{
		return this.result;
	}

	@Override
	public ChunkWalkProgress getProgress()
	{
		return this.result.getProgress();
	}

	/** The placement the list was built for; the reports name it this way. */
	public String getPlacementName()
	{
		return this.getSubject();
	}
}
