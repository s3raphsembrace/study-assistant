package app.study.media;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import app.study.config.MediaProperties;

/**
 * Speech to text, entirely on this machine: ffmpeg decodes anything to the
 * 16 kHz mono WAV whisper.cpp expects, then whisper-cli transcribes it.
 * Both are child processes whose output is parsed for progress.
 */
@Service
public class Transcriber {

	private static final Logger log = LoggerFactory.getLogger(Transcriber.class);

	/** One spoken passage with its start time in seconds. */
	public record Segment(double start, double end, String text) {}

	public record Result(List<Segment> segments, double durationSeconds) {

		public String text() {
			StringBuilder sb = new StringBuilder();
			for (Segment s : segments) {
				if (sb.length() > 0) sb.append(' ');
				sb.append(s.text());
			}
			return sb.toString();
		}
	}

	/** Progress callback: fraction done and a message for the job. */
	@FunctionalInterface
	public interface Progress {
		void report(double fraction, String message);
	}

	private static final Pattern FFMPEG_DURATION = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
	private static final Pattern FFMPEG_TIME = Pattern.compile("time=\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");
	private static final Pattern WHISPER_LINE = Pattern.compile(
			"^\\[(\\d+):(\\d+):(\\d+\\.\\d+)\\s*-->\\s*(\\d+):(\\d+):(\\d+\\.\\d+)]\\s*(.*)$");

	private final MediaProperties props;
	private final MediaTools tools;

	public Transcriber(MediaProperties props, MediaTools tools) {
		this.props = props;
		this.tools = tools;
	}

	/**
	 * Decode any audio or video file to 16 kHz mono 16-bit WAV.
	 *
	 * @return the media's duration in seconds, or 0 when ffmpeg didn't report it
	 */
	public double toWav(Path input, Path output, Progress progress) throws IOException {
		Path ffmpeg = tools.ffmpeg().orElseThrow(() ->
				new IOException("ffmpeg isn't installed yet. Install the media tools first."));
		Files.createDirectories(output.getParent());

		List<String> cmd = List.of(ffmpeg.toString(), "-hide_banner", "-nostdin", "-y",
				"-i", input.toString(), "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
				output.toString());
		double[] duration = { 0 };
		StringBuilder tail = new StringBuilder();

		int exit = run(cmd, null, line -> {
			// ffmpeg writes everything to stderr: the header carries the duration,
			// then one progress line per second of output.
			Matcher d = FFMPEG_DURATION.matcher(line);
			if (d.find()) duration[0] = seconds(d.group(1), d.group(2), d.group(3));
			Matcher t = FFMPEG_TIME.matcher(line);
			if (t.find() && duration[0] > 0 && progress != null) {
				double done = seconds(t.group(1), t.group(2), t.group(3));
				progress.report(Math.min(1, done / duration[0]),
						"Decoding audio… " + clock(done) + " of " + clock(duration[0]));
			}
			if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
			tail.append(line).append('\n');
		});
		if (exit != 0 || !Files.isRegularFile(output)) {
			throw new IOException("ffmpeg couldn't decode this file. " + lastLine(tail.toString()));
		}
		return duration[0];
	}

	/** Transcribe a 16 kHz mono WAV produced by {@link #toWav}. */
	public Result transcribe(Path wav, double durationSeconds, Progress progress) throws IOException {
		Path whisper = tools.whisper().orElseThrow(() ->
				new IOException("whisper.cpp isn't installed yet. Install the media tools first."));
		Path model = tools.model().orElseThrow(() ->
				new IOException("The speech model isn't downloaded yet. Install the media tools first."));

		List<String> cmd = new ArrayList<>(List.of(whisper.toString(),
				"-m", model.toString(),
				"-f", wav.toString(),
				"-t", String.valueOf(props.threads()),
				"-np"));
		if (!"auto".equalsIgnoreCase(props.language())) {
			cmd.add("-l");
			cmd.add(props.language());
		}

		List<Segment> segments = new ArrayList<>();
		StringBuilder tail = new StringBuilder();
		int exit = run(cmd, line -> {
			Matcher m = WHISPER_LINE.matcher(line.trim());
			if (!m.matches()) return;
			double start = seconds(m.group(1), m.group(2), m.group(3));
			double end = seconds(m.group(4), m.group(5), m.group(6));
			String text = m.group(7).trim();
			if (text.isEmpty() || text.equals("[BLANK_AUDIO]")) return;
			segments.add(new Segment(start, end, text));
			if (progress != null && durationSeconds > 0) {
				progress.report(Math.min(1, end / durationSeconds),
						"Transcribing… " + clock(end) + " of " + clock(durationSeconds));
			} else if (progress != null) {
				progress.report(0, "Transcribing… " + clock(end));
			}
		}, line -> {
			if (tail.length() > 4000) tail.delete(0, tail.length() - 4000);
			tail.append(line).append('\n');
		});

		if (exit != 0) throw new IOException("whisper.cpp failed. " + lastLine(tail.toString()));
		if (segments.isEmpty()) {
			throw new IOException("No speech was recognised in this recording.");
		}
		double end = segments.get(segments.size() - 1).end();
		log.info("Transcribed {} segments covering {}", segments.size(), clock(end));
		return new Result(segments, durationSeconds > 0 ? durationSeconds : end);
	}

	/**
	 * Group segments into parts of roughly {@code app.media.segment-seconds},
	 * each prefixed with its timestamp, so notes sections map to points in the
	 * recording and the source view stays readable.
	 */
	public List<String> toParts(List<Segment> segments) {
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		double partStart = segments.isEmpty() ? 0 : segments.get(0).start();
		for (Segment s : segments) {
			if (cur.length() > 0 && s.end() - partStart > props.segmentSeconds()) {
				parts.add("[" + clock(partStart) + "] " + cur);
				cur.setLength(0);
				partStart = s.start();
			}
			if (cur.length() > 0) cur.append(' ');
			cur.append(s.text());
		}
		if (cur.length() > 0) parts.add("[" + clock(partStart) + "] " + cur);
		return parts;
	}

	public static String clock(double seconds) {
		int total = (int) Math.round(seconds);
		int h = total / 3600;
		int m = (total % 3600) / 60;
		int s = total % 60;
		return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
	}

	private static double seconds(String h, String m, String s) {
		return Integer.parseInt(h) * 3600 + Integer.parseInt(m) * 60 + Double.parseDouble(s);
	}

	private static String lastLine(String s) {
		String[] lines = s.strip().split("\\R");
		return lines.length == 0 ? "" : lines[lines.length - 1].strip();
	}

	/** Run a child process, streaming stdout and stderr to the given consumers. */
	private static int run(List<String> cmd, java.util.function.Consumer<String> onOut,
			java.util.function.Consumer<String> onErr) throws IOException {
		log.debug("Running {}", String.join(" ", cmd));
		Process p = new ProcessBuilder(cmd).start();
		Thread errThread = Thread.ofVirtual().start(() -> drain(p.getErrorStream(), onErr));
		drain(p.getInputStream(), onOut);
		try {
			errThread.join();
			return p.waitFor();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
			throw new IOException("Interrupted while running " + cmd.get(0), e);
		}
	}

	private static void drain(java.io.InputStream in, java.util.function.Consumer<String> sink) {
		try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				if (sink != null) sink.accept(line);
			}
		} catch (IOException e) {
			log.debug("Process stream ended: {}", e.toString());
		}
	}
}
