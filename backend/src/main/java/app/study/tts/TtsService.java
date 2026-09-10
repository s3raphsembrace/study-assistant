package app.study.tts;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import tools.jackson.databind.ObjectMapper;

import app.study.config.AppProperties;
import app.study.config.TtsProperties;

/**
 * Offline text-to-speech through Kokoro. Reports what is and isn't installed
 * so the UI can guide the user, downloads the model files only when asked,
 * and turns long text into one WAV file with progress.
 */
@Service
public class TtsService {

	private static final Logger log = LoggerFactory.getLogger(TtsService.class);

	public record Status(
			boolean pythonFound,
			boolean kokoroInstalled,
			boolean modelPresent,
			boolean voicesPresent,
			boolean running,
			String modelPath,
			String voicesPath,
			List<String> voices,
			String defaultVoice,
			String problem,
			/** Everything needed to synthesize is in place. */
			boolean ready) {
	}

	public record Result(Path file, int sampleRate, long bytes, double seconds) {}

	private final TtsProperties props;
	private final AppProperties app;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

	private volatile KokoroSidecar sidecar;
	private volatile Boolean pythonOk;
	private volatile Boolean kokoroOk;

	public TtsService(TtsProperties props, AppProperties app, ObjectMapper json) {
		this.props = props;
		this.app = app;
		this.json = json;
	}

	@PreDestroy
	void shutdown() {
		KokoroSidecar s = sidecar;
		if (s != null) s.stop();
	}

	public String defaultVoice() {
		return props.defaultVoice();
	}

	public Status status() {
		boolean py = pythonFound();
		boolean kk = py && kokoroInstalled();
		Optional<Path> model = findFile(modelCandidates());
		Optional<Path> voices = findFile(voicesCandidates());
		KokoroSidecar s = sidecar;
		boolean running = s != null && s.isRunning();
		String missing = model.isEmpty() && voices.isEmpty() ? "voice model (about 340 MB, one time)"
				: model.isEmpty() ? "model file kokoro-v1.0.onnx (about 310 MB)"
				: voices.isEmpty() ? "voices file voices-v1.0.bin (about 27 MB)" : null;
		String problem = !py ? "Python 3 wasn't found (looked for \"" + props.python() + "\"). Install it from python.org."
				: !kk ? "The kokoro-onnx package isn't installed. Run:  pip install kokoro-onnx"
				: missing != null ? "Kokoro's " + missing + " isn't downloaded yet."
				: null;
		boolean ready = py && kk && model.isPresent() && voices.isPresent();
		return new Status(py, kk, model.isPresent(), voices.isPresent(), running,
				model.map(Path::toString).orElse(null), voices.map(Path::toString).orElse(null),
				running ? s.voiceIds() : List.of(), props.defaultVoice(), problem, ready);
	}

	/** Downloads the model files into tools/kokoro. Only ever called from a user action. */
	public void downloadModels(DoubleConsumer progress) throws IOException {
		Path dir = kokoroDir();
		Files.createDirectories(dir);
		if (findFile(modelCandidates()).isEmpty()) {
			download(props.modelUrl(), dir.resolve("kokoro-v1.0.onnx"), p -> progress.accept(p * 0.9));
		}
		if (findFile(voicesCandidates()).isEmpty()) {
			download(props.voicesUrl(), dir.resolve("voices-v1.0.bin"), p -> progress.accept(0.9 + p * 0.1));
		}
		progress.accept(1.0);
	}

	/** Our own download location always wins over the configured fallbacks. */
	private Path kokoroDir() {
		return app.toolsPath().resolve("kokoro");
	}

	private List<String> modelCandidates() {
		List<String> out = new ArrayList<>();
		out.add(kokoroDir().resolve("kokoro-v1.0.onnx").toString());
		out.addAll(props.modelPaths());
		return out;
	}

	private List<String> voicesCandidates() {
		List<String> out = new ArrayList<>();
		out.add(kokoroDir().resolve("voices-v1.0.bin").toString());
		out.addAll(props.voicesPaths());
		return out;
	}

	/** Synthesize {@code text} into {@code out} (WAV, mono, 16-bit). */
	public Result synthesizeToFile(String text, String voice, double speed, Path out, DoubleConsumer progress)
			throws IOException {
		KokoroSidecar s = sidecarOrThrow();
		String v = voice != null && !voice.isBlank() ? voice : props.defaultVoice();
		if (!s.voiceIds().isEmpty() && !s.voiceIds().contains(v)) {
			throw new IOException("Unknown voice \"" + v + "\". Available: " + String.join(", ", s.voiceIds()));
		}

		List<String> batches = batch(forSpeech(text), props.batchChars());
		if (batches.isEmpty()) throw new IOException("There is no text to read.");

		ByteArrayOutputStream pcm = new ByteArrayOutputStream();
		for (int i = 0; i < batches.size(); i++) {
			pcm.write(s.synthesize(batches.get(i), v, speed));
			progress.accept((i + 1) / (double) batches.size());
		}
		byte[] data = pcm.toByteArray();
		int rate = s.sampleRate();
		Files.createDirectories(out.getParent());
		try (OutputStream o = Files.newOutputStream(out)) {
			o.write(wavHeader(data.length, rate));
			o.write(data);
		}
		double seconds = data.length / 2.0 / rate;
		return new Result(out, rate, data.length + 44L, seconds);
	}

	// ---- internals ---------------------------------------------------------

	private KokoroSidecar sidecarOrThrow() throws IOException {
		Status st = status();
		if (!st.ready()) throw new IOException(st.problem());
		KokoroSidecar s = sidecar;
		if (s == null || !s.isRunning()) {
			synchronized (this) {
				s = sidecar;
				if (s == null || !s.isRunning()) {
					s = new KokoroSidecar(props.python(), app.dataPath().resolve("tts"),
							Path.of(st.modelPath()), Path.of(st.voicesPath()), json);
					s.ensureStarted();
					sidecar = s;
				}
			}
		}
		return s;
	}

	private boolean pythonFound() {
		if (pythonOk != null) return pythonOk;
		pythonOk = run(props.python(), "--version");
		return pythonOk;
	}

	private boolean kokoroInstalled() {
		if (kokoroOk != null) return kokoroOk;
		kokoroOk = run(props.python(), "-c", "import kokoro_onnx");
		return kokoroOk;
	}

	private static boolean run(String... cmd) {
		try {
			Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	/** Anything smaller is a failed download (a "Not Found" page, a .part stub), not a model. */
	private static final long MIN_MODEL_BYTES = 1L << 20;

	private static Optional<Path> findFile(List<String> candidates) {
		String home = System.getProperty("user.home");
		for (String c : candidates) {
			Path p = Path.of(c.startsWith("~") ? home + c.substring(1) : c).toAbsolutePath().normalize();
			if (!Files.isRegularFile(p)) continue;
			try {
				if (Files.size(p) < MIN_MODEL_BYTES) {
					log.warn("Ignoring {}: only {} bytes, looks like a failed download", p, Files.size(p));
					continue;
				}
			} catch (IOException e) {
				continue;
			}
			return Optional.of(p);
		}
		return Optional.empty();
	}

	private void download(String url, Path target, DoubleConsumer progress) throws IOException {
		log.info("Downloading {} -> {}", url, target);
		HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(30)).GET().build();
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		try {
			HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (res.statusCode() != 200) throw new IOException("Download failed with HTTP " + res.statusCode() + " for " + url);
			long total = res.headers().firstValueAsLong("content-length").orElse(-1);
			try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[1 << 16];
				long done = 0;
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					done += n;
					if (total > 0) progress.accept(Math.min(1.0, done / (double) total));
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted", e);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Last line of defence before the voice: strip any Markdown the "spoken
	 * version" pass left behind. Without this a leftover heading is read out as
	 * "hash hash key takeaways", and asterisks become audible noise.
	 */
	static String forSpeech(String text) {
		String t = text.replace("\r\n", "\n");
		t = t.replaceAll("(?m)^[ \\t]*@slides\\[[^\\]]*\\][ \\t]*$", "");
		t = t.replaceAll("```[\\s\\S]*?```", " ");
		t = t.replaceAll("(?m)^[ \\t]{0,3}#{1,6}[ \\t]*", "");        // headings
		t = t.replaceAll("(?m)^[ \\t]*>[ \\t]?", "");                  // block quotes
		t = t.replaceAll("(?m)^[ \\t]*[-*+][ \\t]+", "");              // bullets
		t = t.replaceAll("(?m)^[ \\t]*\\d+\\.[ \\t]+", "");            // numbered items
		t = t.replaceAll("(?m)^[ \\t]*([-*_])([ \\t]*\\1){2,}[ \\t]*$", ""); // rules
		t = t.replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "");               // images
		t = t.replaceAll("\\[([^\\]]+)]\\([^)]*\\)", "$1");            // links keep their text
		t = t.replaceAll("(\\*\\*|__)(.+?)\\1", "$2");                 // bold
		t = t.replaceAll("(?<![\\w*])[*_](?!\\s)(.+?)(?<!\\s)[*_](?![\\w*])", "$1"); // italics
		t = t.replaceAll("`([^`]+)`", "$1");
		t = t.replaceAll("\\|", " ");                                  // table pipes
		t = t.replaceAll("[ \\t]{2,}", " ");
		// Removals above can leave whitespace-only lines; drop them so the
		// paragraph collapse below sees real blank lines.
		t = t.replaceAll("(?m)[ \\t]+$", "");
		return t.replaceAll("\\n{3,}", "\n\n").strip();
	}

	/** Group sentences into batches of roughly {@code maxChars}, never splitting a sentence. */
	static List<String> batch(String text, int maxChars) {
		List<String> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String para : text.split("\\n\\s*\\n")) {
			String p = para.replaceAll("\\s+", " ").trim();
			if (p.isEmpty()) continue;
			for (String sentence : p.split("(?<=[.!?])\\s+")) {
				if (cur.length() > 0 && cur.length() + sentence.length() + 1 > maxChars) {
					out.add(cur.toString());
					cur.setLength(0);
				}
				if (cur.length() > 0) cur.append(' ');
				cur.append(sentence);
			}
			// Paragraph end: flush so the voice pauses naturally.
			if (cur.length() > maxChars / 2) {
				out.add(cur.toString());
				cur.setLength(0);
			}
		}
		if (cur.length() > 0) out.add(cur.toString());
		return out;
	}

	static byte[] wavHeader(int dataLength, int sampleRate) {
		ByteBuffer b = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
		b.put("RIFF".getBytes()).putInt(36 + dataLength).put("WAVE".getBytes());
		b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
				.putInt(sampleRate).putInt(sampleRate * 2).putShort((short) 2).putShort((short) 16);
		b.put("data".getBytes()).putInt(dataLength);
		return b.array();
	}
}
