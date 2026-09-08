package app.study.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class TextCleanerTest {

	private final TextCleaner cleaner = new TextCleaner();

	@Test
	void repairsHyphenationAcrossLines() {
		List<String> out = cleaner.cleanPages(List.of("Digital quantifi-\ncation of targets"));
		assertThat(out.get(0)).isEqualTo("Digital quantification of targets");
	}

	@Test
	void dropsRunningHeadersRepeatedAcrossPages() {
		String header = "BME 260 - Lecture 3\n";
		List<String> pages = List.of(
				header + "Slide one content\n1",
				header + "Slide two content\n2",
				header + "Slide three content\n3",
				header + "Slide four content\n4");
		List<String> out = cleaner.cleanPages(pages);
		assertThat(out).allSatisfy(p -> {
			assertThat(p).doesNotContain("BME 260");
			assertThat(p).doesNotContainPattern("(?m)^\\d$");
		});
		assertThat(out.get(2)).isEqualTo("Slide three content");
	}

	@Test
	void keepsRepeatedLinesOnShortDocuments() {
		// Below the page threshold we can't tell a header from a genuinely repeated point.
		List<String> out = cleaner.cleanPages(List.of("Key idea\nA", "Key idea\nB"));
		assertThat(out.get(0)).contains("Key idea");
	}

	@Test
	void normalisesBulletsAndWhitespace() {
		List<String> out = cleaner.cleanPages(List.of("•   First   point\n\n\n\n▪ Second point   "));
		assertThat(out.get(0)).isEqualTo("- First point\n\n- Second point");
	}

	@Test
	void stripsPageNumberVariants() {
		List<String> out = cleaner.cleanPages(List.of("Body\nPage 12\n12 / 40\n- 7 -\n3 of 9"));
		assertThat(out.get(0)).isEqualTo("Body");
	}

	@Test
	void dropsSentencesRepeatedByOverlappingTextLayers() {
		String s1 = "Real-time PCR measures the amount of product after each round of amplification.";
		String s2 = "The exponential phase represents the most efficient phase of amplification.";
		String page = s1 + " " + s2 + "\n\nDraft layer below.\n" + s1 + "\n" + s2 + " Short.";
		String out = TextCleaner.dedupeSentences(page);
		assertThat(out).containsOnlyOnce(s1).containsOnlyOnce(s2);
		assertThat(out).contains("Draft layer below.").contains("Short.");
	}

	@Test
	void joinPagesSkipsBlanksAndSeparatesWithBlankLine() {
		assertThat(cleaner.joinPages(List.of("A", "", "  ", "B"))).isEqualTo("A\n\nB");
	}

	@Test
	void stripsTrailingReferencesOnly() {
		// The bibliography must sit in the last half of the text to be recognised.
		String body = "Intro paragraph.\n\n" + "More body text here that is long enough. ".repeat(6) + "\n\n";
		String refs = "References\n1. Smith J. Some paper. 2019.\n2. Doe A. Another. 2020.";
		assertThat(cleaner.stripReferences(body + refs)).isEqualTo(body.trim());
		// A References heading early in the text (e.g. a slide about referencing) is left alone.
		String early = "References\nHow to cite sources.\n\n" + "Body ".repeat(50);
		assertThat(cleaner.stripReferences(early)).isEqualTo(early);
	}
}
