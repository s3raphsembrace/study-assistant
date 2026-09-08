package app.study.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import app.study.config.OllamaProperties;

/**
 * Thin client for Ollama's local HTTP API: {@code /api/chat} (streamed or not,
 * optionally constrained to a JSON schema), {@code /api/tags} and
 * {@code /api/embeddings}. Uses the JDK HttpClient so the app carries no extra
 * dependency, and translates the failure modes a user can act on into
 * {@link LlmException}s with instructions.
 */
@Component
public class OllamaClient {

	private static final Logger log = LoggerFactory.getLogger(OllamaClient.class);

	/** Output cap applied when a caller doesn't set one. */
	static final int DEFAULT_MAX_TOKENS = 4096;

	public record Message(String role, String content) {
		public static Message system(String c) { return new Message("system", c); }
		public static Message user(String c) { return new Message("user", c); }
		public static Message assistant(String c) { return new Message("assistant", c); }
	}

	/** Per-call knobs. {@code schema} switches on Ollama's structured output. */
	public record Options(String model, Double temperature, Integer numPredict, JsonNode schema) {
		public Options withModel(String m) { return new Options(m, temperature, numPredict, schema); }
	}

	/** Token counts Ollama reports once a reply is complete. */
	public record Usage(int promptTokens, int completionTokens, long totalNanos) {}

	public record Reply(String content, Usage usage) {}

	private final OllamaProperties props;
	private final ObjectMapper json;
	private final HttpClient http;

	public OllamaClient(OllamaProperties props, ObjectMapper json) {
		this.props = props;
		this.json = json;
		this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	public String baseUrl() {
		return props.baseUrl();
	}

	/** True when Ollama answers on its port. Never throws. */
	public boolean isReachable() {
		try {
			HttpResponse<Void> res = http.send(get("/api/tags", Duration.ofSeconds(3)),
					HttpResponse.BodyHandlers.discarding());
			return res.statusCode() == 200;
		} catch (Exception e) {
			return false;
		}
	}

	/** Tags of every model Ollama has pulled locally. */
	public List<String> listModels() {
		try {
			HttpResponse<String> res = http.send(get("/api/tags", Duration.ofSeconds(5)),
					HttpResponse.BodyHandlers.ofString());
			if (res.statusCode() != 200) throw unreachable(null);
			List<String> out = new ArrayList<>();
			for (JsonNode m : json.readTree(res.body()).path("models")) {
				String name = m.path("name").asText(null);
				if (name != null) out.add(name);
			}
			return out;
		} catch (IOException | InterruptedException e) {
			throw unreachable(e);
		}
	}

	/** One-shot chat completion. */
	public Reply chat(List<Message> messages, Options opts) {
		return chat(messages, opts, null);
	}

	/**
	 * Chat completion; when {@code onDelta} is given the reply is streamed and
	 * each token delta is passed along as it arrives (used for progress).
	 */
	public Reply chat(List<Message> messages, Options opts, Consumer<String> onDelta) {
		boolean stream = onDelta != null;
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("model", opts.model() != null ? opts.model() : props.chatModel());
		body.put("messages", messages);
		body.put("stream", stream);
		if (opts.schema() != null) body.put("format", opts.schema());
		Map<String, Object> options = new LinkedHashMap<>();
		options.put("num_ctx", props.numCtx());
		options.put("repeat_penalty", props.repeatPenalty());
		if (opts.temperature() != null) options.put("temperature", opts.temperature());
		// Never leave output unbounded: a looping model would otherwise run until the timeout.
		options.put("num_predict", opts.numPredict() != null ? opts.numPredict() : DEFAULT_MAX_TOKENS);
		body.put("options", options);
		// Keep the model resident between chunks; reloading it costs seconds each time.
		body.put("keep_alive", "10m");

		HttpRequest req;
		try {
			req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/api/chat"))
					.timeout(Duration.ofSeconds(props.requestTimeoutSeconds()))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
					.build();
		} catch (JacksonException e) {
			throw new LlmException(LlmException.Kind.UNKNOWN, "Couldn't encode the request.", e);
		}

		try {
			HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (res.statusCode() != 200) {
				throw fromErrorBody(res.statusCode(), readAll(res.body()), (String) body.get("model"));
			}
			return stream ? readStream(res.body(), onDelta) : readSingle(res.body());
		} catch (HttpTimeoutException e) {
			throw new LlmException(LlmException.Kind.TIMEOUT,
					"The model took longer than " + props.requestTimeoutSeconds() + "s to answer. "
							+ "Try a smaller model or raise app.ollama.request-timeout-seconds.", e);
		} catch (ConnectException e) {
			throw unreachable(e);
		} catch (IOException e) {
			if (e.getCause() instanceof ConnectException) throw unreachable(e);
			throw new LlmException(LlmException.Kind.UNKNOWN, "Talking to Ollama failed: " + e.getMessage(), e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new LlmException(LlmException.Kind.UNKNOWN, "Interrupted while waiting for the model.", e);
		}
	}

	/** Embedding vectors, one per input text (Phase 5). */
	public List<float[]> embed(List<String> texts) {
		List<float[]> out = new ArrayList<>(texts.size());
		for (String t : texts) {
			try {
				String payload = json.writeValueAsString(Map.of("model", props.embedModel(), "prompt", t));
				HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/api/embeddings"))
						.timeout(Duration.ofSeconds(120))
						.header("Content-Type", "application/json")
						.POST(HttpRequest.BodyPublishers.ofString(payload))
						.build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() != 200) throw fromErrorBody(res.statusCode(), res.body(), props.embedModel());
				JsonNode arr = json.readTree(res.body()).path("embedding");
				float[] v = new float[arr.size()];
				for (int i = 0; i < v.length; i++) v[i] = (float) arr.get(i).asDouble();
				out.add(v);
			} catch (IOException e) {
				throw unreachable(e);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new LlmException(LlmException.Kind.UNKNOWN, "Interrupted while embedding.", e);
			}
		}
		return out;
	}

	// ---- internals ---------------------------------------------------------

	private HttpRequest get(String path, Duration timeout) {
		return HttpRequest.newBuilder(URI.create(props.baseUrl() + path)).timeout(timeout).GET().build();
	}

	private Reply readSingle(InputStream in) throws IOException {
		JsonNode node = json.readTree(readAll(in));
		return new Reply(node.path("message").path("content").asText(""), usage(node));
	}

	private Reply readStream(InputStream in, Consumer<String> onDelta) throws IOException {
		StringBuilder full = new StringBuilder();
		Usage usage = new Usage(0, 0, 0);
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (line.isBlank()) continue;
				JsonNode node;
				try {
					node = json.readTree(line);
				} catch (JacksonException e) {
					log.debug("Skipping malformed NDJSON line: {}", line);
					continue;
				}
				if (node.has("error")) {
					throw new LlmException(LlmException.Kind.UNKNOWN, "Ollama: " + node.get("error").asText());
				}
				String delta = node.path("message").path("content").asText("");
				if (!delta.isEmpty()) {
					full.append(delta);
					onDelta.accept(delta);
				}
				if (node.path("done").asBoolean(false)) usage = usage(node);
			}
		}
		return new Reply(full.toString(), usage);
	}

	private static Usage usage(JsonNode node) {
		return new Usage(node.path("prompt_eval_count").asInt(0), node.path("eval_count").asInt(0),
				node.path("total_duration").asLong(0));
	}

	private static String readAll(InputStream in) throws IOException {
		try (in) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private LlmException fromErrorBody(int status, String body, String model) {
		String detail = body;
		try {
			JsonNode n = json.readTree(body);
			if (n.has("error")) detail = n.get("error").asText();
		} catch (JacksonException ignored) {
			/* not JSON */
		}
		if (status == 404 || (detail != null && detail.toLowerCase().contains("not found"))) {
			return new LlmException(LlmException.Kind.MODEL_MISSING,
					"Model \"" + model + "\" isn't available in Ollama. Pull it with:  ollama pull " + model);
		}
		return new LlmException(LlmException.Kind.UNKNOWN, "Ollama returned HTTP " + status + ": " + detail);
	}

	private LlmException unreachable(Throwable cause) {
		return new LlmException(LlmException.Kind.UNREACHABLE,
				"Ollama isn't reachable at " + props.baseUrl() + ". Start it (run `ollama serve`, or open the "
						+ "Ollama app) or install it from https://ollama.com/download.",
				cause);
	}
}
