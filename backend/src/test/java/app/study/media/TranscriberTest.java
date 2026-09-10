package app.study.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import app.study.config.MediaProperties;

class TranscriberTest {

	/** segment-seconds = 60 so the grouping is easy to reason about. */
	private final Transcriber transcriber = new Transcriber(
			new MediaProperties(null, null, null, null, null, "en", 4, 60), null);

	@Test
	void clockFormatsMinutesAndHours() {
		assertThat(Transcriber.clock(9)).isEqualTo("0:09");
		assertThat(Transcriber.clock(75)).isEqualTo("1:15");
		assertThat(Transcriber.clock(3725)).isEqualTo("1:02:05");
	}

	@Test
	void groupsSegmentsIntoTimestampedParts() {
		List<Transcriber.Segment> segs = List.of(
				new Transcriber.Segment(0, 20, "First thought."),
				new Transcriber.Segment(20, 40, "Second thought."),
				new Transcriber.Segment(40, 58, "Third thought."),
				new Transcriber.Segment(60, 80, "After the break."),
				new Transcriber.Segment(80, 100, "Still going."));
		List<String> parts = transcriber.toParts(segs);

		assertThat(parts).hasSize(2);
		assertThat(parts.get(0)).isEqualTo("[0:00] First thought. Second thought. Third thought.");
		assertThat(parts.get(1)).startsWith("[1:00] After the break.");
	}

	@Test
	void emptyInputProducesNoParts() {
		assertThat(transcriber.toParts(List.of())).isEmpty();
	}
}
