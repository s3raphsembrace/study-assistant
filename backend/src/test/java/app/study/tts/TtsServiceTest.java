package app.study.tts;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import org.junit.jupiter.api.Test;

class TtsServiceTest {

	@Test
	void batchesSentencesWithoutSplittingThem() {
		String text = "One two three. Four five six. Seven eight nine.\n\nNew paragraph here. And more.";
		List<String> batches = TtsService.batch(text, 32);
		assertThat(batches).isNotEmpty();
		assertThat(batches).allSatisfy(b -> assertThat(b).doesNotStartWith(" "));
		assertThat(String.join(" ", batches).replace("  ", " "))
				.contains("One two three.")
				.contains("Seven eight nine.")
				.contains("And more.");
		// No batch is wildly over budget (a single sentence may exceed it, nothing else).
		assertThat(batches).allSatisfy(b -> assertThat(b.length()).isLessThanOrEqualTo(48));
	}

	@Test
	void flushesAtParagraphEndsOnceHalfFull() {
		// Small budget: a paragraph end past the half-way mark ends the batch.
		assertThat(TtsService.batch("Para one sentence.\n\nPara two sentence.", 30))
				.containsExactly("Para one sentence.", "Para two sentence.");
		// Large budget: short paragraphs are merged rather than sent one by one.
		assertThat(TtsService.batch("Para one sentence.\n\nPara two sentence.", 1000))
				.containsExactly("Para one sentence. Para two sentence.");
	}

	@Test
	void wavHeaderDescribesMono16BitPcm() {
		byte[] h = TtsService.wavHeader(1000, 24000);
		ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
		assertThat(h).hasSize(44);
		assertThat(new String(h, 0, 4)).isEqualTo("RIFF");
		assertThat(b.getInt(4)).isEqualTo(36 + 1000);
		assertThat(new String(h, 8, 4)).isEqualTo("WAVE");
		assertThat(b.getShort(22)).isEqualTo((short) 1);      // channels
		assertThat(b.getInt(24)).isEqualTo(24000);            // sample rate
		assertThat(b.getInt(28)).isEqualTo(48000);            // byte rate
		assertThat(b.getShort(34)).isEqualTo((short) 16);     // bits per sample
		assertThat(b.getInt(40)).isEqualTo(1000);             // data length
	}
}
