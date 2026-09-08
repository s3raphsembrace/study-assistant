package app.study.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfExtractorTest {

	@TempDir
	Path tmp;

	@Test
	void extractsEachPageAndReportsProgress() throws Exception {
		Path pdf = tmp.resolve("two-pages.pdf");
		try (PDDocument doc = new PDDocument()) {
			for (String line : List.of("First page text", "Second page text")) {
				PDPage page = new PDPage();
				doc.addPage(page);
				try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
					cs.beginText();
					cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
					cs.newLineAtOffset(72, 700);
					cs.showText(line);
					cs.endText();
				}
			}
			doc.getDocumentInformation().setTitle("Probe Deck");
			doc.save(pdf.toFile());
		}

		List<int[]> progress = new ArrayList<>();
		PdfExtractor.Extraction ex = new PdfExtractor().extract(pdf, (p, total) -> progress.add(new int[] { p, total }));

		assertThat(ex.pageCount()).isEqualTo(2);
		assertThat(ex.metadataTitle()).isEqualTo("Probe Deck");
		assertThat(ex.pages().get(0)).contains("First page text");
		assertThat(ex.pages().get(1)).contains("Second page text");
		assertThat(progress).containsExactly(new int[] { 1, 2 }, new int[] { 2, 2 });
	}

	@Test
	void garbleScoreFlagsInterleavedLayers() {
		String clean = "Real-time PCR measures the amount of PCR product after each round of amplification.";
		String garbled = "the exponenSteinasolr sp20h18a, s18e,.1 2T71he absolute amount of target in a qPCR reactio2no fi2s7";
		assertThat(PdfExtractor.garbleScore(clean)).isZero();
		assertThat(PdfExtractor.garbleScore(garbled)).isGreaterThan(0.1);
		assertThat(PdfExtractor.choose(garbled, clean)).isEqualTo(clean);
	}

	@Test
	void prefersSortedOutputWhenBothAreClean() {
		String sorted = "Title\nBullet one\nBullet two";
		String stream = "Bullet two\nTitle\nBullet one";
		assertThat(PdfExtractor.choose(sorted, stream)).isEqualTo(sorted);
	}
}
