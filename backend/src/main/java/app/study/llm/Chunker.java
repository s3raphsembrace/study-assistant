package app.study.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Token budgeting so any document fits a model's context window. Token
 * counts are estimated at ~4 characters per token, which is conservative for
 * English prose; the request-level {@code num_ctx} is the real guard.
 *
 * <p>Chunks break on paragraph boundaries first, then sentences, then hard
 * character limits, and carry a small overlap so ideas that straddle a seam
 * are seen in full by at least one call.
 */
public final class Chunker {

	private static final int CHARS_PER_TOKEN = 4;

	private Chunker() {}

	public static int estimateTokens(String text) {
		return (int) Math.ceil(text.length() / (double) CHARS_PER_TOKEN);
	}

	/** Head of the text, capped to a token budget. */
	public static String capTokens(String text, int maxTokens) {
		int maxChars = maxTokens * CHARS_PER_TOKEN;
		if (text.length() <= maxChars) return text;
		int cut = text.lastIndexOf("\n\n", maxChars);
		if (cut < maxChars / 2) cut = text.lastIndexOf(' ', maxChars);
		if (cut < maxChars / 2) cut = maxChars;
		return text.substring(0, cut).trim();
	}

	public static List<String> chunk(String text, int maxTokens, int overlapTokens) {
		int maxChars = Math.max(400, maxTokens * CHARS_PER_TOKEN);
		String trimmed = text.trim();
		if (trimmed.isEmpty()) return List.of();
		if (trimmed.length() <= maxChars) return List.of(trimmed);

		List<String> units = splitUnits(trimmed, maxChars);
		List<String> chunks = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int overlapChars = Math.max(0, overlapTokens) * CHARS_PER_TOKEN;

		for (String u : units) {
			if (cur.length() > 0 && cur.length() + u.length() + 2 > maxChars) {
				String done = cur.toString().trim();
				chunks.add(done);
				cur.setLength(0);
				// Carry the tail of the finished chunk forward, but only when it
				// still leaves room for the next unit; otherwise the overlap would
				// be emitted as a chunk of its own.
				if (overlapChars > 0 && done.length() > overlapChars) {
					String tail = done.substring(done.length() - overlapChars);
					int nl = tail.indexOf('\n');
					String carry = nl >= 0 ? tail.substring(nl + 1) : tail;
					if (!carry.isBlank() && carry.length() + u.length() + 2 <= maxChars) {
						cur.append(carry).append("\n\n");
					}
				}
			}
			if (cur.length() > 0 && !cur.toString().endsWith("\n\n")) cur.append("\n\n");
			cur.append(u);
		}
		if (!cur.toString().isBlank()) chunks.add(cur.toString().trim());
		return chunks;
	}

	/** Paragraphs, with any single paragraph over budget split on sentences, then hard-sliced. */
	private static List<String> splitUnits(String text, int maxChars) {
		List<String> out = new ArrayList<>();
		for (String para : text.split("\\n\\s*\\n")) {
			String p = para.trim();
			if (p.isEmpty()) continue;
			if (p.length() <= maxChars) {
				out.add(p);
				continue;
			}
			StringBuilder buf = new StringBuilder();
			for (String s : p.split("(?<=[.!?])\\s+")) {
				if (s.length() > maxChars) {
					if (buf.length() > 0) { out.add(buf.toString().trim()); buf.setLength(0); }
					for (int i = 0; i < s.length(); i += maxChars) {
						out.add(s.substring(i, Math.min(s.length(), i + maxChars)));
					}
					continue;
				}
				if (buf.length() + s.length() + 1 > maxChars) {
					out.add(buf.toString().trim());
					buf.setLength(0);
				}
				if (buf.length() > 0) buf.append(' ');
				buf.append(s);
			}
			if (buf.length() > 0) out.add(buf.toString().trim());
		}
		return out;
	}
}
