package app.study.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class GenerationServiceTest {

	private final ObjectMapper json = new ObjectMapper();

	@Test
	void tidyQuizRecoversOptionsWrittenIntoTheQuestion() {
		JsonNode qs = json.readTree("""
				[{"type":"mcq","question":"Which is true?\\\\nA) First choice\\\\nB) Second choice\\\\nC) Third\\\\nD) Fourth",
				  "options":["A","B","C","D"],"correctIndex":1}]
				""");
		GenerationService.tidyQuiz(qs);
		JsonNode q = qs.get(0);
		assertThat(q.get("question").asText()).isEqualTo("Which is true?");
		assertThat(q.get("options")).hasSize(4);
		assertThat(q.get("options").get(1).asText()).isEqualTo("Second choice");
		assertThat(q.get("correctIndex").asInt()).isEqualTo(1);
	}

	@Test
	void tidyQuizStripsDuplicatedOptionLinesButKeepsGoodOptions() {
		JsonNode qs = json.readTree("""
				[{"type":"mcq","question":"Pick one.\\nA) Alpha\\nB) Beta\\nC) Gamma\\nD) Delta",
				  "options":["Alpha","Beta","Gamma","Delta"],"correctIndex":0}]
				""");
		GenerationService.tidyQuiz(qs);
		assertThat(qs.get(0).get("question").asText()).isEqualTo("Pick one.");
		assertThat(qs.get(0).get("options").get(0).asText()).isEqualTo("Alpha");
	}

	@Test
	void tidyQuizLeavesOtherTypesAlone() {
		JsonNode qs = json.readTree("""
				[{"type":"true_false","question":"Water boils at 100 C at sea level.","options":["True","False"],"correctIndex":0}]
				""");
		GenerationService.tidyQuiz(qs);
		assertThat(qs.get(0).get("question").asText()).isEqualTo("Water boils at 100 C at sea level.");
		assertThat(qs.get(0).get("options")).hasSize(2);
	}

	@Test
	void unfenceAndStripLeadingSummary() {
		assertThat(GenerationService.unfence("```markdown\n## A\ntext\n```")).isEqualTo("## A\ntext");
		assertThat(GenerationService.stripLeadingSummary("## Summary\nBlah blah.\n\n## Real Heading\nBody"))
				.isEqualTo("## Real Heading\nBody");
		assertThat(GenerationService.stripLeadingSummary("## Real Heading\nBody")).isEqualTo("## Real Heading\nBody");
	}
}
