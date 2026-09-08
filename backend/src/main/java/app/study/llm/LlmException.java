package app.study.llm;

/**
 * A user-actionable failure talking to the local model runtime: it's not
 * running, the model isn't pulled, or the reply couldn't be parsed. The
 * message is written for the UI, not the log.
 */
public class LlmException extends RuntimeException {

	public enum Kind { UNREACHABLE, MODEL_MISSING, BAD_RESPONSE, TIMEOUT, UNKNOWN }

	private final Kind kind;

	public LlmException(Kind kind, String message) {
		super(message);
		this.kind = kind;
	}

	public LlmException(Kind kind, String message, Throwable cause) {
		super(message, cause);
		this.kind = kind;
	}

	public Kind kind() {
		return kind;
	}
}
