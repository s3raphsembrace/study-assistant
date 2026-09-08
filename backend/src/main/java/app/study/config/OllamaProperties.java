package app.study.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code app.ollama.*}. The defaults match the models already
 * pulled on a typical install; the chat model can be overridden at runtime
 * from the settings API (stored in SQLite) without touching this file.
 *
 * @param baseUrl       where Ollama listens
 * @param chatModel     default chat model tag
 * @param embedModel    embedding model tag (used in Phase 5 for chat-with-notes)
 * @param numCtx        context window to request; the KV cache for larger
 *                      values may not fit a 4 GB GPU
 * @param chunkTokens   input budget per generation call, in estimated tokens
 * @param overlapTokens overlap carried between consecutive chunks
 * @param requestTimeoutSeconds per-call ceiling; a chunk on a slow CPU can take minutes
 * @param repeatPenalty  sampling penalty against repeating tokens; small models
 *                       otherwise fall into loops on long outputs (1.0 = off)
 */
@ConfigurationProperties(prefix = "app.ollama")
public record OllamaProperties(
		String baseUrl,
		String chatModel,
		String embedModel,
		int numCtx,
		int chunkTokens,
		int overlapTokens,
		int requestTimeoutSeconds,
		double repeatPenalty) {

	public OllamaProperties {
		if (baseUrl == null || baseUrl.isBlank()) baseUrl = "http://127.0.0.1:11434";
		baseUrl = baseUrl.replaceAll("/+$", "");
		if (chatModel == null || chatModel.isBlank()) chatModel = "qwen2.5:3b";
		if (embedModel == null || embedModel.isBlank()) embedModel = "nomic-embed-text";
		if (numCtx <= 0) numCtx = 8192;
		if (chunkTokens <= 0) chunkTokens = 3000;
		if (overlapTokens < 0) overlapTokens = 0;
		if (requestTimeoutSeconds <= 0) requestTimeoutSeconds = 900;
		if (repeatPenalty <= 0) repeatPenalty = 1.1;
	}
}
