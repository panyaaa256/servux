package fi.dy.masa.servux.scheduler.session;

import javax.annotation.Nullable;

/**
 * The kinds of server side task a client can ask for.
 * <p>
 * The name doubles as the capability string advertised in the {@code Features} metadata
 * list and as the {@code /servux} subcommand, so that "does the server do this?", "which
 * session is this?" and "which command starts it?" cannot drift apart.
 * <p>
 * Note that this is never sent over the wire as an ordinal - the {@code Task} strings in
 * the packets are spelled out in full - so entries may be added or reordered freely.
 */
public enum ServerTaskKind
{
	VERIFY("verify", "LitematicaVerify"),
	ANALYZE("analyze", "LitematicaAnalyze");

	private final String name;
	private final String taskPrefix;

	ServerTaskKind(String name, String taskPrefix)
	{
		this.name = name;
		this.taskPrefix = taskPrefix;
	}

	public String getName()
	{
		return this.name;
	}

	/** C2S: start this kind of task. */
	public String requestTask()
	{
		return this.taskPrefix;
	}

	/** C2S: acknowledge a result batch, which pulls the next one. */
	public String ackTask()
	{
		return this.taskPrefix + "Ack";
	}

	/** C2S: abandon the run. */
	public String cancelTask()
	{
		return this.taskPrefix + "Cancel";
	}

	/** S2C: one batch of the result. */
	public String resultTask()
	{
		return this.taskPrefix + "Result";
	}

	/** S2C: progress ping while the walk is still going. */
	public String statusTask()
	{
		return this.taskPrefix + "Status";
	}

	/** S2C: the run could not start or could not finish; carries a translation key. */
	public String errorTask()
	{
		return this.taskPrefix + "Error";
	}

	@Nullable
	public static ServerTaskKind fromName(String name)
	{
		for (ServerTaskKind kind : values())
		{
			if (kind.name.equals(name))
			{
				return kind;
			}
		}

		return null;
	}

	/** The kind whose start request this {@code Task} string is, or null. */
	@Nullable
	public static ServerTaskKind byRequestTask(String task)
	{
		for (ServerTaskKind kind : values())
		{
			if (kind.requestTask().equals(task))
			{
				return kind;
			}
		}

		return null;
	}

	/** The kind whose batch acknowledgement this {@code Task} string is, or null. */
	@Nullable
	public static ServerTaskKind byAckTask(String task)
	{
		for (ServerTaskKind kind : values())
		{
			if (kind.ackTask().equals(task))
			{
				return kind;
			}
		}

		return null;
	}

	/** The kind whose cancellation this {@code Task} string is, or null. */
	@Nullable
	public static ServerTaskKind byCancelTask(String task)
	{
		for (ServerTaskKind kind : values())
		{
			if (kind.cancelTask().equals(task))
			{
				return kind;
			}
		}

		return null;
	}
}
