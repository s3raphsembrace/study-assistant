package app.study.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.study.config.AppProperties;
import app.study.config.MediaProperties;
import app.study.util.Downloader;

/**
 * Finds (or installs) the three external tools media ingestion needs:
 * ffmpeg for decoding, whisper.cpp for transcription, and yt-dlp for YouTube.
 * Anything already on PATH is used as-is; the rest is downloaded into
 * {@code tools/}, and only ever when the user presses the button.
 */
@Service
public class MediaTools {

	private static final Logger log = LoggerFactory.getLogger(MediaTools.class);

	/** Below this a file is a failed download, not a real binary or model. */
	private static final long MIN_BYTES = 1L << 20;

	public record Status(
			String ffmpeg,
			String ytdlp,
			String whisper,
			String model,
			String modelName,
			boolean transcriptionReady,
			boolean youtubeReady,
			String problem,
			long downloadMegabytes) {}

	private final MediaProperties props;
	private final AppProperties app;
	private final Downloader downloader;

	public MediaTools(MediaProperties props, AppProperties app, Downloader downloader) {
		this.props = props;
		this.app = app;
		this.downloader = downloader;
	}

	// ---- locating ----------------------------------------------------------

	public Path toolsDir() {
		return app.toolsPath();
	}

	public Optional<Path> ffmpeg() {
		return find("ffmpeg", toolsDir().resolve("ffmpeg"));
	}

	public Optional<Path> ytdlp() {
		return find("yt-dlp", toolsDir().resolve("ytdlp"));
	}

	public Optional<Path> whisper() {
		return find("whisper-cli", toolsDir().resolve("whisper"));
	}

	public Optional<Path> model() {
		Path p = toolsDir().resolve("whisper").resolve(props.modelFileName());
		return bigEnough(p) ? Optional.of(p) : Optional.empty();
	}

	public Status status() {
		Optional<Path> ff = ffmpeg();
		Optional<Path> yt = ytdlp();
		Optional<Path> wh = whisper();
		Optional<Path> md = model();
		boolean transcription = ff.isPresent() && wh.isPresent() && md.isPresent();
		boolean youtube = yt.isPresent() && ff.isPresent();

		long mb = 0;
		if (ff.isEmpty()) mb += 111;
		if (wh.isEmpty()) mb += 21;
		if (md.isEmpty()) mb += props.whisperModel().startsWith("small") ? 488 : 148;
		if (yt.isEmpty()) mb += 18;

		String problem = transcription && youtube ? null
				: "Audio and YouTube need ffmpeg, whisper.cpp and yt-dlp (" + mb + " MB, one time).";
		return new Status(ff.map(Path::toString).orElse(null), yt.map(Path::toString).orElse(null),
				wh.map(Path::toString).orElse(null), md.map(Path::toString).orElse(null),
				props.whisperModel(), transcription, youtube, problem, mb);
	}

	// ---- installing --------------------------------------------------------

	/** Download whatever is missing. Long-running; called from a job. */
	public void install(DoubleConsumer progress, java.util.function.Consumer<String> status) throws IOException {
		record Step(String label, double weight, IoRunnable action) {}
		List<Step> steps = List.of(
				new Step("ffmpeg", 111, () -> {
					if (ffmpeg().isPresent()) return;
					Path zip = toolsDir().resolve("ffmpeg").resolve("ffmpeg.zip");
					downloader.download(props.ffmpegUrl(), zip, p -> {});
					// The archive carries ffplay and docs too; keep just what we run.
					int n = downloader.unzipFlat(zip, zip.getParent(),
							name -> name.equalsIgnoreCase("ffmpeg.exe") || name.equalsIgnoreCase("ffprobe.exe")
									|| name.equals("ffmpeg") || name.equals("ffprobe"));
					Files.deleteIfExists(zip);
					if (n == 0) throw new IOException("The ffmpeg archive didn't contain an ffmpeg executable.");
					makeExecutable(zip.getParent());
				}),
				new Step("whisper.cpp", 21, () -> {
					if (whisper().isPresent()) return;
					Path zip = toolsDir().resolve("whisper").resolve("whisper.zip");
					downloader.download(props.whisperBinUrl(), zip, p -> {});
					// whisper-cli plus the runtime libraries it loads.
					int n = downloader.unzipFlat(zip, zip.getParent(),
							name -> name.startsWith("whisper-cli") || name.endsWith(".dll") || name.endsWith(".so")
									|| name.endsWith(".dylib"));
					Files.deleteIfExists(zip);
					if (n == 0) throw new IOException("The whisper.cpp archive didn't contain whisper-cli.");
					makeExecutable(zip.getParent());
				}),
				new Step("the speech model", 148, () -> {
					if (model().isPresent()) return;
					downloader.download(props.resolvedModelUrl(),
							toolsDir().resolve("whisper").resolve(props.modelFileName()), p -> {});
				}),
				new Step("yt-dlp", 18, () -> {
					if (ytdlp().isPresent()) return;
					Path bin = toolsDir().resolve("ytdlp")
							.resolve(isWindows() ? "yt-dlp.exe" : "yt-dlp");
					downloader.download(props.ytdlpUrl(), bin, p -> {});
					makeExecutable(bin.getParent());
				}));

		double total = steps.stream().mapToDouble(Step::weight).sum();
		double done = 0;
		for (Step s : steps) {
			status.accept("Downloading " + s.label() + "…");
			double base = done / total;
			double span = s.weight() / total;
			progress.accept(base);
			s.action().run();
			done += s.weight();
			progress.accept(base + span);
		}
		progress.accept(1.0);
		status.accept("Media tools ready");
	}

	@FunctionalInterface
	private interface IoRunnable {
		void run() throws IOException;
	}

	// ---- internals ---------------------------------------------------------

	private Optional<Path> find(String name, Path ownDir) {
		String exe = isWindows() ? name + ".exe" : name;
		Path own = ownDir.resolve(exe);
		if (Files.isRegularFile(own)) return Optional.of(own);
		return onPath(name);
	}

	private static Optional<Path> onPath(String name) {
		try {
			Process p = new ProcessBuilder(isWindows() ? "where" : "which", name)
					.redirectErrorStream(true).start();
			String out = new String(p.getInputStream().readAllBytes()).trim();
			if (!p.waitFor(10, TimeUnit.SECONDS) || p.exitValue() != 0 || out.isEmpty()) return Optional.empty();
			Path first = Path.of(out.split("\\R")[0].trim());
			return Files.isRegularFile(first) ? Optional.of(first) : Optional.empty();
		} catch (IOException e) {
			return Optional.empty();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return Optional.empty();
		}
	}

	private static boolean bigEnough(Path p) {
		try {
			return Files.isRegularFile(p) && Files.size(p) >= MIN_BYTES;
		} catch (IOException e) {
			return false;
		}
	}

	static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	/** No-op on Windows; elsewhere the extracted binaries need the executable bit. */
	private static void makeExecutable(Path dir) {
		if (isWindows()) return;
		try (var files = Files.list(dir)) {
			files.filter(Files::isRegularFile).forEach(f -> {
				try {
					f.toFile().setExecutable(true);
				} catch (Exception e) {
					log.debug("Couldn't mark {} executable", f);
				}
			});
		} catch (IOException e) {
			log.debug("Couldn't list {}", dir);
		}
	}
}
