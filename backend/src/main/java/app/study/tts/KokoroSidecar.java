package app.study.tts;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns one {@code kokoro_server.py} child process: extracts the script from
 * the jar, launches it on a free port, waits for its health check, and speaks
 * to it. Not a Spring bean itself; {@link TtsService} holds the instance so it
 * can be torn down on shutdown.
 */
class KokoroSidecar {

	private static final Logger log = LoggerFactory.getLogger(KokoroSidecar.class);
	private static final Duration STARTUP_TIMEOUT = Duration.ofSeconds(90);

	private final String python;
	private final Path scriptDir;
	private final Path model;
	private final Path voices;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	private Process process;
	private int port;
	private List<String> voiceIds = List.of();
	private int sampleRate = 24000;

	KokoroSidecar(String python, Path scriptDir, Path model, Path voices, ObjectMapper json) {
		this.python = python;
		this.scriptDir = scriptDir;
		this.model = model;
		this.voices = voices;
		this.json = json;
	}

	synchronized boolean isRunning() {
		return process != null && process.isAlive();
	}

	List<String> voiceIds() {
		return voiceIds;
	}

	int sampleRate() {
		return sampleRate;
	}

	/** Launch if needed and block until the health check passes. */
	synchronized void ensureStarted() throws IOException {
		if (isRunning()) return;
		Path script = extractScript();
		ProcessBuilder pb = new ProcessBuilder(python, script.toString(),
				"--port", "0", "--model", model.toString(), "--voices", voices.toString());
		pb.redirectErrorStream(false);
		pb.environment().put("PYTHONIOENCODING", "utf-8");
		log.info("Starting Kokoro sidecar: {} {}", python, script);
		process = pb.start();

		// Drain stderr in the background so the child never blocks on a full pipe.
		Thread drain = new Thread(() -> drainStderr(process.getErrorStream()), "kokoro-stderr");
		drain.setDaemon(true);
		drain.start();

		port = readPort(process.getInputStream());
		waitHealthy();
		log.info("Kokoro sidecar ready on port {} with {} voices", port, voiceIds.size());
	}

	synchronized void stop() {
		if (process == null) return;
		process.destroy();
		try {
			if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			process.destroyForcibly();
		}
		process = null;
	}

	/** Synthesize one batch of text to signed 16-bit PCM at {@link #sampleRate()}. */
	byte[] synthesize(String text, String voice, double speed) throws IOException {
		String body = json.writeValueAsString(Map.of("text", text, "voice", voice, "speed", speed, "lang", "en-us"));
		HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/tts"))
				.timeout(Duration.ofMinutes(5))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
		try {
			HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
			if (res.statusCode() != 200) {
				String err = new String(res.body(), StandardCharsets.UTF_8);
				try {
					err = json.readTree(err).path("error").asText(err);
				} catch (Exception ignored) {
					/* not JSON */
				}
				throw new IOException("Kokoro failed: " + err);
			}
			res.headers().firstValue("X-Sample-Rate").ifPresent(v -> sampleRate = Integer.parseInt(v));
			return res.body();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while synthesizing speech", e);
		}
	}

	// ---- internals ---------------------------------------------------------

	private Path extractScript() throws IOException {
		Files.createDirectories(scriptDir);
		Path target = scriptDir.resolve("kokoro_server.py");
		try (InputStream in = new ClassPathResource("tts/kokoro_server.py").getInputStream()) {
			Files.write(target, in.readAllBytes());
		}
		return target;
	}

	private int readPort(InputStream stdout) throws IOException {
		BufferedReader r = new BufferedReader(new InputStreamReader(stdout, StandardCharsets.UTF_8));
		long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT.toMillis();
		String line;
		while (System.currentTimeMillis() < deadline && (line = r.readLine()) != null) {
			if (line.startsWith("PORT ")) {
				int p = Integer.parseInt(line.substring(5).trim());
				Thread keep = new Thread(() -> drainStdout(r), "kokoro-stdout");
				keep.setDaemon(true);
				keep.start();
				return p;
			}
		}
		stop();
		throw new IOException("Kokoro sidecar didn't report a port. " + lastStderr());
	}

	private void waitHealthy() throws IOException {
		long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT.toMillis();
		IOException last = null;
		while (System.currentTimeMillis() < deadline) {
			try {
				HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
						.timeout(Duration.ofSeconds(5)).GET().build();
				HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
				if (res.statusCode() == 200) {
					JsonNode n = json.readTree(res.body());
					List<String> ids = new ArrayList<>();
					n.path("voices").forEach(v -> ids.add(v.asText()));
					voiceIds = List.copyOf(ids);
					sampleRate = n.path("sampleRate").asInt(24000);
					return;
				}
			} catch (IOException e) {
				last = e;
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new IOException("Interrupted while starting Kokoro", e);
			}
			if (!process.isAlive()) break;
			try {
				Thread.sleep(300);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		stop();
		throw new IOException("Kokoro sidecar didn't become healthy. " + lastStderr(), last);
	}

	private final StringBuilder stderr = new StringBuilder();

	private void drainStderr(InputStream in) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				synchronized (stderr) {
					if (stderr.length() > 4000) stderr.delete(0, stderr.length() - 4000);
					stderr.append(line).append('\n');
				}
				log.debug("kokoro: {}", line);
			}
		} catch (IOException ignored) {
			/* process ended */
		}
	}

	private static void drainStdout(BufferedReader r) {
		try {
			while (r.readLine() != null) {
				/* discard */
			}
		} catch (IOException ignored) {
			/* process ended */
		}
	}

	private String lastStderr() {
		synchronized (stderr) {
			String s = stderr.toString().trim();
			return s.isEmpty() ? "" : "Sidecar output: " + s;
		}
	}
}
