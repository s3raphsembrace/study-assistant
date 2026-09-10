package app.study.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.function.DoubleConsumer;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Downloads and unpacks the optional tools the app can install for itself
 * (Kokoro voices, ffmpeg, yt-dlp, whisper.cpp). Everything lands in
 * {@code tools/}, and downloads only ever start from an explicit user action.
 */
@Component
public class Downloader {

	private static final Logger log = LoggerFactory.getLogger(Downloader.class);

	private final HttpClient http = HttpClient.newBuilder()
			.followRedirects(HttpClient.Redirect.NORMAL)
			.connectTimeout(Duration.ofSeconds(15))
			.build();

	/** Fetch {@code url} to {@code target}, reporting 0..1 progress. Writes via a .part file. */
	public void download(String url, Path target, DoubleConsumer progress) throws IOException {
		log.info("Downloading {} -> {}", url, target);
		Files.createDirectories(target.getParent());
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		HttpRequest req = HttpRequest.newBuilder(URI.create(url))
				.timeout(Duration.ofMinutes(60))
				.header("user-agent", "study-assistant")
				.GET().build();
		try {
			HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
			if (res.statusCode() != 200) {
				throw new IOException("Download failed with HTTP " + res.statusCode() + " for " + url);
			}
			long total = res.headers().firstValueAsLong("content-length").orElse(-1);
			try (InputStream in = res.body(); OutputStream out = Files.newOutputStream(tmp)) {
				byte[] buf = new byte[1 << 16];
				long done = 0;
				int n;
				while ((n = in.read(buf)) > 0) {
					out.write(buf, 0, n);
					done += n;
					if (total > 0 && progress != null) progress.accept(Math.min(1.0, done / (double) total));
				}
			}
			Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
			if (progress != null) progress.accept(1.0);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Download interrupted", e);
		} finally {
			Files.deleteIfExists(tmp);
		}
	}

	/**
	 * Extract a zip into {@code dir}, flattening directories and keeping only
	 * entries whose file name {@code keep} accepts. Returns the number written.
	 */
	public int unzipFlat(Path zip, Path dir, Predicate<String> keep) throws IOException {
		Files.createDirectories(dir);
		int written = 0;
		try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
			ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				if (e.isDirectory()) continue;
				String name = Path.of(e.getName()).getFileName().toString();
				if (keep != null && !keep.test(name)) continue;
				Path out = dir.resolve(name);
				// Defence in depth: a crafted name must not escape the target dir.
				if (!out.normalize().startsWith(dir.normalize())) continue;
				Files.copy(zin, out, StandardCopyOption.REPLACE_EXISTING);
				written++;
			}
		}
		return written;
	}
}
