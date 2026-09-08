package app.study.ingest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/**
 * Pulls the raw text out of a PDF page by page with PDFBox. Pure Java, no
 * native code. Output is uncleaned: see {@link TextCleaner} for the pass that
 * removes running headers, slide numbers and hyphenation artefacts.
 *
 * <p>Each page is extracted twice: sorted by position (best for slide decks,
 * whose text boxes are rarely in reading order in the content stream) and in
 * content-stream order (best for PDFs with overlapping text layers, where
 * sorting interleaves the layers into gibberish). The variant with fewer
 * broken-looking tokens wins, page by page.
 */
@Component
public class PdfExtractor {

	/** Progress callback: 1-based page just read, and the total page count. */
	@FunctionalInterface
	public interface PageListener {
		void onPage(int page, int total);
	}

	/** Raw per-page text plus whatever title the PDF metadata carries. */
	public record Extraction(String metadataTitle, List<String> pages) {
		public int pageCount() { return pages.size(); }
	}

	/** A token like "sp20h18a" or "exponenSteinasolr": letters wrapped around digits, or a mid-word case flip. */
	private static final Pattern BROKEN_TOKEN = Pattern.compile(
			"\\p{L}+\\d+\\p{L}+|\\p{Ll}{2,}\\p{Lu}\\p{Ll}{2,}\\p{Lu}");
	private static final Pattern WORD = Pattern.compile("[\\p{L}\\d]{2,}");
	/** Sorted output must be clearly worse before we switch modes for a page. */
	private static final double GARBLE_MARGIN = 0.01;

	/** @param onPage invoked after each page is read so callers can report progress. */
	public Extraction extract(Path file, PageListener onPage) throws IOException {
		try (PDDocument doc = Loader.loadPDF(file.toFile())) {
			PDFTextStripper sorted = newStripper(true);
			PDFTextStripper stream = newStripper(false);

			int total = doc.getNumberOfPages();
			List<String> pages = new ArrayList<>(total);
			for (int p = 1; p <= total; p++) {
				String bySorted = pageText(sorted, doc, p);
				String byStream = pageText(stream, doc, p);
				pages.add(choose(bySorted, byStream));
				if (onPage != null) onPage.onPage(p, total);
			}

			PDDocumentInformation info = doc.getDocumentInformation();
			String title = info != null ? info.getTitle() : null;
			return new Extraction(title == null || title.isBlank() ? null : title.trim(), pages);
		}
	}

	private static PDFTextStripper newStripper(boolean sortByPosition) throws IOException {
		PDFTextStripper s = new PDFTextStripper();
		s.setSortByPosition(sortByPosition);
		s.setLineSeparator("\n");
		s.setParagraphEnd("\n");
		return s;
	}

	private static String pageText(PDFTextStripper s, PDDocument doc, int page) throws IOException {
		s.setStartPage(page);
		s.setEndPage(page);
		return s.getText(doc);
	}

	static String choose(String sorted, String stream) {
		double gs = garbleScore(sorted);
		double gt = garbleScore(stream);
		return gs > gt + GARBLE_MARGIN ? stream : sorted;
	}

	/** Fraction of word-like tokens that look mangled. 0 for clean prose. */
	static double garbleScore(String text) {
		int words = 0;
		int broken = 0;
		var m = WORD.matcher(text);
		while (m.find()) {
			words++;
			if (BROKEN_TOKEN.matcher(m.group()).find()) broken++;
		}
		return words == 0 ? 0 : (double) broken / words;
	}
}
