package app.study.tts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TtsSpeechTextTest {

	@Test
	void headingsLoseTheirHashesInsteadOfBeingReadOut() {
		assertThat(TtsService.forSpeech("## Key Takeaways\nFirst point."))
				.isEqualTo("Key Takeaways\nFirst point.");
	}

	@Test
	void listsAndEmphasisBecomePlainProse() {
		String md = "- **Enzymes** speed reactions.\n- They are *catalysts*.\n1. First\n2. Second";
		assertThat(TtsService.forSpeech(md))
				.isEqualTo("Enzymes speed reactions.\nThey are catalysts.\nFirst\nSecond");
	}

	@Test
	void linksKeepTheirTextAndImagesDisappear() {
		assertThat(TtsService.forSpeech("See [the paper](https://example.com/x) ![chart](c.png) now."))
				.isEqualTo("See the paper now.");
	}

	@Test
	void slideMarkersAndCodeFencesAreRemoved() {
		assertThat(TtsService.forSpeech("@slides[1-4]\n\nBefore.\n\n```\ncode();\n```\n\nAfter."))
				.isEqualTo("Before.\n\nAfter.");
	}

	@Test
	void plainProseIsUntouched() {
		String plain = "The rate depends on substrate concentration. It saturates at high values.";
		assertThat(TtsService.forSpeech(plain)).isEqualTo(plain);
	}
}
