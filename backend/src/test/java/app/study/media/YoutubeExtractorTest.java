package app.study.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class YoutubeExtractorTest {

	@Test
	void recognisesEveryUrlFormYoutubeUses() {
		assertThat(YoutubeExtractor.videoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
		assertThat(YoutubeExtractor.videoId("https://youtu.be/dQw4w9WgXcQ?t=42")).contains("dQw4w9WgXcQ");
		assertThat(YoutubeExtractor.videoId("youtube.com/shorts/dQw4w9WgXcQ")).contains("dQw4w9WgXcQ");
		assertThat(YoutubeExtractor.videoId("https://m.youtube.com/watch?feature=x&v=dQw4w9WgXcQ"))
				.contains("dQw4w9WgXcQ");
		assertThat(YoutubeExtractor.videoId("https://www.youtube-nocookie.com/embed/dQw4w9WgXcQ"))
				.contains("dQw4w9WgXcQ");
	}

	@Test
	void rejectsOtherLinks() {
		assertThat(YoutubeExtractor.videoId("https://vimeo.com/12345")).isEmpty();
		assertThat(YoutubeExtractor.videoId("https://www.youtube.com/watch?v=tooshort")).isEmpty();
		assertThat(YoutubeExtractor.videoId("not a url")).isEmpty();
		assertThat(YoutubeExtractor.videoId("")).isEmpty();
		assertThat(YoutubeExtractor.videoId(null)).isEmpty();
	}

	@Test
	void vttBecomesProseWithoutTimingsOrRollingDuplicates() {
		String vtt = """
				WEBVTT
				Kind: captions
				Language: en

				1
				00:00:00.000 --> 00:00:02.000
				Today we look at <c>enzyme</c> kinetics.

				2
				00:00:02.000 --> 00:00:04.000
				Today we look at enzyme kinetics.
				The rate depends on substrate concentration.
				""";
		String text = YoutubeExtractor.vttToText(vtt);
		assertThat(text).isEqualTo(
				"Today we look at enzyme kinetics. The rate depends on substrate concentration.");
	}

	@Test
	void decodesHtmlEntitiesInCaptions() {
		String vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nSalt &amp; water &lt;100&gt;\n";
		assertThat(YoutubeExtractor.vttToText(vtt)).isEqualTo("Salt & water <100>");
	}
}
