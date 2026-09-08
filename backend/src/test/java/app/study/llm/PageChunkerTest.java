package app.study.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PageChunkerTest {

	@Test
	void groupsWholePagesAndRemembersTheirNumbers() {
		String page = "w".repeat(1000); // 250 tokens
		List<PageChunker.Section> s = PageChunker.chunk(List.of(page, page, page, "", page, page), 600, 0);
		// 600 tokens = two pages per section; the blank page 4 is skipped but the
		// numbering stays intact, so pages 3 and 5 share the "3-5" section.
		assertThat(s).hasSize(3);
		assertThat(s.get(0).firstPage()).isEqualTo(1);
		assertThat(s.get(0).lastPage()).isEqualTo(2);
		assertThat(s.get(1).pageRange()).isEqualTo("3-5");
		assertThat(s.get(2).pageRange()).isEqualTo("6");
	}

	@Test
	void capsPagesPerSectionForSlideDecks() {
		List<String> slides = java.util.Collections.nCopies(20, "short slide text");
		List<PageChunker.Section> s = PageChunker.chunk(slides, 3000, 8);
		assertThat(s).hasSize(3);
		assertThat(s.get(0).pageRange()).isEqualTo("1-8");
		assertThat(s.get(2).pageRange()).isEqualTo("17-20");
	}

	@Test
	void splitsAnOversizedPageKeepingItsNumber() {
		String huge = ("Sentence number one is here. ").repeat(200); // ~1450 tokens
		List<PageChunker.Section> s = PageChunker.chunk(List.of("intro", huge, "outro"), 500, 0);
		assertThat(s.get(0).pageRange()).isEqualTo("1");
		assertThat(s.subList(1, s.size() - 1)).allSatisfy(x -> assertThat(x.pageRange()).isEqualTo("2"));
		assertThat(s.get(s.size() - 1).pageRange()).isEqualTo("3");
	}

	@Test
	void slideMarkersRoundTrip() {
		PageChunker.Section sec = new PageChunker.Section("x", 3, 7);
		String md = GenerationService.slidesMarker(sec) + "\n\n## Heading\ntext";
		assertThat(md).startsWith("@slides[3-7]");
		assertThat(GenerationService.stripSlideRefs(md)).isEqualTo("## Heading\ntext");
	}
}
