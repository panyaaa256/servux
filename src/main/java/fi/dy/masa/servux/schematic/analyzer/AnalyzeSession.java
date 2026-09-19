package fi.dy.masa.servux.schematic.analyzer;

import java.util.UUID;
import javax.annotation.Nullable;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerLevel;

import fi.dy.masa.servux.scheduler.ChunkWalkProgress;
import fi.dy.masa.servux.scheduler.session.ServerTaskKind;
import fi.dy.masa.servux.scheduler.session.ServerTaskSession;

/** One in-flight (or just finished) area analysis. */
public class AnalyzeSession extends ServerTaskSession
{
	private final AnalyzeResult result;

	public AnalyzeSession(UUID sessionId, UUID owner, String areaName, ServerLevel level,
	                      AnalyzeResult result, @Nullable CommandSourceStack source)
	{
		super(sessionId, owner, ServerTaskKind.ANALYZE, areaName, level, source);

		this.result = result;
	}

	public AnalyzeResult getResult()
	{
		return this.result;
	}

	@Override
	public ChunkWalkProgress getProgress()
	{
		return this.result.getProgress();
	}

	/** The area being analysed; the reports name it this way. */
	public String getAreaName()
	{
		return this.getSubject();
	}
}
