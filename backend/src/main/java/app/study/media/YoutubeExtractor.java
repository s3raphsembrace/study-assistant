package app.study.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Pulls a lecture off YouTube with yt-dlp: existing captions when the video
 * has them (instant), otherwise the audio track for local transcription.
 * Everything runs as a child process on this machine; nothing is uploaded.
 */
@Service
public class YoutubeExtractor {

	private static final Logger log = LoggerFactory.getLogger(YoutubeExtractor.class);

	/** Either {@code captions} or {@code audio} is set, never both. */
	public record Extraction(String title, double durationSeconds, String captions, Path audio) {}

	private static final Pattern DOWNLOAD_PERCENT = Pattern.compile("\\[download]\\s+(\\d+(?:\\.\\d+)?)%");
	private static final Pattern VTT_CUE = Pattern.compile("-->" );
	private static final Pattern TAG = Pattern.compile("<[^>]*>");

	private final MediaTools tools;

	public YoutubeExtractor(MediaTools tools) {
		this.tools = tools;
	}

	/** Recognises the URL forms YouTube uses; returns the 11-character video id. */
	public static Optional<String> videoId(String url) {
		if (url == null || url.isBlank()) return Optional.empty();
		String u = url.trim();
		if (!u.matches("(?i)^https?://.*")) u = "https://" + u;
		try {
			java.net.URI uri = java.net.URI.create(u);
			String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^(www\\.|m\\.)", "");
			String path = uri.getPath() == null ? "" : uri.getPath();
			if (host.equals("youtu.be")) return valid(path.replaceFirst("^/", "").split("/")[0]);
			if (host.equals("youtube.com") || host.equals("youtube-nocookie.com")) {
				if (path.equals("/watch")) {
					String q = uri.getQuery() == null ? "" : uri.getQuery();
					for (String kv : q.split("&")) {
						if (kv.startsWith("v=")) return valid(kv.substring(2));
					}
					return Optional.empty();
				}
				Matcher m = Pattern.compile("^/(?:embed|shorts|live|v)/([A-Za-z0-9_-]{11})").matcher(path);
				if (m.find()) return Optional.of(m.group(1));
			}
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		return Optional.empty();
	}

	private static Optional<String> valid(String id) {
		return id != null && id.matches("[A-Za-z0-9_-]{11}") ? Optional.of(id) : Optional.empty();
	}

	public static boolean isYoutube(String url) {
		return videoId(url).isPresent();
	}

	/**
	 * Fetch title, then captions, then (if there are none) the audio track
	 * already decoded to the 16 kHz mono WAV whisper wants.
	 *
	 * @param workDir a directory this method may fill with temporary files
	 */
	public Extraction extract(String url, Path workDir, Transcriber.Progress progress) throws IOException {
		Path yt = tools.ytdlp().orElseThrow(() ->
				new IOException("yt-dlp isn't installed yet. Install the media tools first."));
		Path ffmpegDir = tools.ffmpeg().map(Path::getParent).orElse(null);
		Files.createDirectories(workDir);

		progress.report(0.02, "Looking up the video…");
		List<String> meta = new ArrayList<>();
		run(List.of(yt.toString(), "--no-warnings", "--no-playlist",
				"--print", "%(title)s", "--print", "%(duration)s", url), meta::add, null);
		String title = meta.isEmpty() ? null : meta.get(0).strip();
		double duration = 0;
		if (meta.size() > 1) {
			try {
				duration = Double.parseDouble(meta.get(1).strip());
			} catch (NumberFormatException ignored) {
				/* live stream or unknown */
			}
		}
		if (title == null || title.isBlank()) {
			throw new IOException("yt-dlp couldn't read that video. Check the link, or update yt-dlp.");
		}

		// 1. Captions, human-written or auto-generated: instant and accurate.
		progress.report(0.06, "Checking for captions…");
		List<String> capCmd = new ArrayList<>(List.of(yt.toString(), "--no-warnings", "--no-playlist",
				"--skip-download", "--write-subs", "--write-auto-subs",
				"--sub-langs", "en.*,en", "--sub-format", "vtt",
				"-o", workDir.resolve("cap.%(ext)s").toString(), url));
		run(capCmd, null, null);
		Optional<String> captions = readCaptions(workDir);
		if (captions.isPresent()) {
			log.info("Using YouTube captions for \"{}\" ({} chars)", title, captions.get().length());
			return new Extraction(title, duration, captions.get(), null);
		}

		// 2. No captions: download the audio and hand it to whisper.
		if (ffmpegDir == null) {
			throw new IOException("This video has no captions, and ffmpeg isn't installed to extract its audio.");
		}
		progress.report(0.08, "No captions; downloading the audio…");
		Path wav = workDir.resolve("audio.wav");
		List<String> audioCmd = new ArrayList<>(List.of(yt.toString(), "--no-warnings", "--no-playlist",
				"-f", "bestaudio/best", "-x", "--audio-format", "wav",
				"--postprocessor-args", "ExtractAudio:-ar 16000 -ac 1",
				"--ffmpeg-location", ffmpegDir.toString(),
				"-o", workDir.resolve("audio.%(ext)s").toString(), url));
		StringBuilder tail = new StringBuilder();
		int exit = run(audioCmd, line -> {
			Matcher m = DOWNLOAD_PERCENT.matcher(line);
			if (m.find()) {
				double pct = Double.parseDouble(m.group(1)) / 100.0;
				progress.report(0.08 + 0.12 * pct, "Downloading audio… " + Math.round(pct * 100) + "%");
			}
		}, line -> {
			if (tail.length() > 3000) tail.delete(0, tail.length() - 3000);
			tail.append(line).append('\n');
		});
		if (exit != 0 || !Files.isRegularFile(wav)) {
			String why = tail.toString().strip();
			throw new IOException("yt-dlp couldn't get the audio for this video."
					+ (why.isEmpty() ? "" : " " + why.substring(Math.max(0, why.length() - 300))));
		}
		return new Extraction(title, duration, null, wav);
	}

	/** Newest .vtt in the directory, converted to plain text. */
	private Optional<String> readCaptions(Path dir) throws IOException {
		try (Stream<Path> files = Files.list(dir)) {
			List<Path> vtts = files.filter(p -> p.getFileName().toString().endsWith(".vtt")).toList();
			for (Path v : vtts) {
				String text = vttToText(Files.readString(v, StandardCharsets.UTF_8));
				if (text.split("\\s+").length > 20) return Optional.of(text);
			}
		}
		return Optional.empty();
	}

	/**
	 * WEBVTT to prose: drop headers, cue timings and inline karaoke tags, and
	 * collapse the rolling duplicate lines auto-captions emit.
	 */
	static String vttToText(String vtt) {
		List<String> out = new ArrayList<>();
		for (String raw : vtt.split("\\R")) {
			String line = TAG.matcher(raw).replaceAll("").strip();
			if (line.isEmpty() || line.equals("WEBVTT") || VTT_CUE.matcher(line).find()) continue;
			if (line.startsWith("Kind:") || line.startsWith("Language:") || line.startsWith("NOTE")) continue;
			if (line.matches("\\d+")) continue; // cue number
			line = line.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
					.replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'");
			if (!out.isEmpty() && out.get(out.size() - 1).equals(line)) continue;
			out.add(line);
		}
		return String.join(" ", out).replaceAll("\\s{2,}", " ").strip();
	}

	private static int run(List<String> cmd, java.util.function.Consumer<String> onOut,
			java.util.function.Consumer<String> onErr) throws IOException {
		log.debug("Running {}", String.join(" ", cmd));
		Process p = new ProcessBuilder(cmd).start();
		Thread err = Thread.ofVirtual().start(() -> drain(p.getErrorStream(), onErr));
		drain(p.getInputStream(), onOut);
		try {
			err.join();
			return p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new IOException("Interrupted while running yt-dlp", e);
		}
	}

	private static void drain(java.io.InputStream in, java.util.function.Consumer<String> sink) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (sink != null) sink.accept(line);
			}
		} catch (IOException e) {
			log.debug("yt-dlp stream ended: {}", e.toString());
		}
	}
}
