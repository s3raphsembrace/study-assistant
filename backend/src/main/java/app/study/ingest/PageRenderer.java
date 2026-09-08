package app.study.ingest;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntConsumer;
import java.util.stream.Stream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import app.study.config.AppProperties;

/**
 * Renders PDF pages to JPEGs under {@code data/pages/<docId>/<n>.jpg} so the
 * notes can show the slides they were written from. Rendering happens during
 * ingestion, and lazily for documents ingested before this existed.
 */
@Component
public class PageRenderer {

	private static final Logger log = LoggerFactory.getLogger(PageRenderer.class);

	/** Good enough to read slide text at full width, ~150 KB per slide. */
	private static final float DPI = 110f;

	private final AppProperties app;
	/** One lock per document so concurrent thumbnail requests don't render twice. */
	private final Map<Long, Object> locks = new ConcurrentHashMap<>();

	public PageRenderer(AppProperties app) {
		this.app = app;
	}

	public Path dir(Long documentId) {
		return app.dataPath().resolve("pages").resolve(String.valueOf(documentId));
	}

	public Path imagePath(Long documentId, int page) {
		return dir(documentId).resolve(page + ".jpg");
	}

	public boolean hasImages(Long documentId) {
		return Files.isRegularFile(imagePath(documentId, 1));
	}

	/** Render every page that isn't on disk yet. Safe to call repeatedly. */
	public void renderAll(Long documentId, Path pdf, IntConsumer onPage) throws IOException {
		Object lock = locks.computeIfAbsent(documentId, k -> new Object());
		synchronized (lock) {
			try (PDDocument doc = Loader.loadPDF(pdf.toFile())) {
				int total = doc.getNumberOfPages();
				if (allPresent(documentId, total)) return;
				Files.createDirectories(dir(documentId));
				PDFRenderer renderer = new PDFRenderer(doc);
				for (int i = 0; i < total; i++) {
					Path out = imagePath(documentId, i + 1);
					if (!Files.isRegularFile(out)) {
						BufferedImage img = renderer.renderImageWithDPI(i, DPI, ImageType.RGB);
						Path tmp = out.resolveSibling(out.getFileName() + ".part");
						if (!ImageIO.write(img, "jpeg", tmp.toFile())) {
							throw new IOException("No JPEG writer available");
						}
						Files.move(tmp, out);
					}
					if (onPage != null) onPage.accept(i + 1);
				}
			}
		}
	}

	public void delete(Long documentId) {
		Path d = dir(documentId);
		if (!Files.isDirectory(d)) return;
		try (Stream<Path> walk = Files.walk(d)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException e) {
					log.debug("Couldn't delete {}", p);
				}
			});
		} catch (IOException e) {
			log.warn("Couldn't remove page images for document {}", documentId, e);
		}
		locks.remove(documentId);
	}

	private boolean allPresent(Long documentId, int total) {
		for (int p = 1; p <= total; p++) {
			if (!Files.isRegularFile(imagePath(documentId, p))) return false;
		}
		return true;
	}
}
