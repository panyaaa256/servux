package fi.dy.masa.servux.scheduler;

/**
 * The chunk level bookkeeping every chunk walking task keeps, independent of what the task
 * actually does with the chunks.
 * <p>
 * This lives apart from the individual result types so that a task base class can maintain
 * it without knowing anything about the result: a verification, an area analysis and a save
 * all count the same four things, and all of them want to report "processed 412 of 500,
 * 3 never generated" the same way.
 * <p>
 * Only ever touched from the server thread (walking tasks run inside the tick loop), so it
 * is deliberately not synchronized.
 */
public class ChunkWalkProgress
{
	private int totalChunks;
	private int processedChunks;
	private int unloadedChunks;
	private int ungeneratedChunks;

	public int getTotalChunks()
	{
		return this.totalChunks;
	}

	public void setTotalChunks(int totalChunks)
	{
		this.totalChunks = totalChunks;
	}

	public int getProcessedChunks()
	{
		return this.processedChunks;
	}

	public void addProcessedChunk()
	{
		this.processedChunks++;
	}

	/** Chunks that were in range but could not be read, because they were not loaded. */
	public int getUnloadedChunks()
	{
		return this.unloadedChunks;
	}

	public void setUnloadedChunks(int unloadedChunks)
	{
		this.unloadedChunks = unloadedChunks;
	}

	/**
	 * Chunks in range that have never been generated. These are skipped rather than
	 * generated, so that inspecting an area never enlarges the world.
	 */
	public int getUngeneratedChunks()
	{
		return this.ungeneratedChunks;
	}

	public void addUngeneratedChunk()
	{
		this.ungeneratedChunks++;
	}

	/** Chunks that were not read for any reason, whether unloaded or never generated. */
	public int getSkippedChunks()
	{
		return this.unloadedChunks + this.ungeneratedChunks;
	}
}
