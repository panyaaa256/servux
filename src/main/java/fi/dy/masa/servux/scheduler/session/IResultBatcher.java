package fi.dy.masa.servux.scheduler.session;

import java.util.UUID;

import fi.dy.masa.servux.util.data.tag.CompoundData;

/**
 * Hands a finished result to the client a bounded amount at a time.
 * <p>
 * The client acknowledges each batch and that pull produces the next one, so a result of
 * any size crosses the wire without ever being buffered up front, and a client that stops
 * responding simply stops the flow. Results small enough to fit in one packet implement
 * this as a single batch; nothing else about the protocol changes.
 */
public interface IResultBatcher
{
	/** True while batches remain to be produced. */
	boolean hasMore();

	/** How many batches have been produced so far. */
	int getBatchNumber();

	/**
	 * Produces the next batch.
	 * <p>
	 * The final batch is marked as such and carries the run's totals, so the client knows
	 * the stream is complete and can fill in its summary in one go.
	 *
	 * @param maxPositions the batch's size budget, in whatever unit the result counts in
	 */
	CompoundData nextBatch(int maxPositions, UUID sessionId);
}
