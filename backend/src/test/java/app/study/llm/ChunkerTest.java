package app.study.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkerTest {

	@Test
	void shortTextIsOneChunk() {
		assertThat(Chunker.chunk("Hello world.", 100, 10)).containsExactly("Hello world.");
		assertThat(Chunker.chunk("   ", 100, 10)).isEmpty();
	}

	@Test
	void splitsOnParagraphsWithinBudget() {
		String para = "word ".repeat(60).trim(); // ~300 chars = 75 tokens
		String text = String.join("\n\n", para, para, para, para);
		List<String> chunks = Chunker.chunk(text, 160, 0); // 640 chars: two paragraphs per chunk
		assertThat(chunks).hasSize(2);
		assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(640));
	}

	// The chunker enforces a 400-char floor, so budgets below use maxTokens=100 (400 chars).
	private static final String A = "x".repeat(200) + ". " + "y".repeat(190) + "."; // 393 chars

	@Test
	void carriesOverlapBetweenChunksWhenItFits() {
		String b = "Beta one.";
		List<String> chunks = Chunker.chunk(A + "\n\n" + b, 100, 20); // 80-char overlap
		assertThat(chunks).hasSize(2);
		assertThat(chunks.get(0)).isEqualTo(A);
		assertThat(chunks.get(1)).startsWith("y".repeat(79) + ".").endsWith(b);
	}

	@Test
	void dropsOverlapInsteadOfEmittingItAlone() {
		String b = "z".repeat(330) + "."; // overlap + b would exceed the budget
		List<String> chunks = Chunker.chunk(A + "\n\n" + b, 100, 20);
		assertThat(chunks).containsExactly(A, b);
	}

	@Test
	void hardSplitsAGiantSentence() {
		String giant = "x".repeat(2000);
		List<String> chunks = Chunker.chunk(giant, 100, 0); // 400 chars each
		assertThat(chunks).hasSize(5);
		assertThat(String.join("", chunks)).isEqualTo(giant);
	}

	@Test
	void capTokensPrefersParagraphBoundary() {
		String text = "First paragraph.\n\nSecond paragraph that is a bit longer.\n\nThird.";
		String capped = Chunker.capTokens(text, 12); // 48 chars
		assertThat(capped).isEqualTo("First paragraph.\n\nSecond paragraph that is a bit longer.".substring(0, capped.length()));
		assertThat(capped.length()).isLessThanOrEqualTo(48);
	}
}
