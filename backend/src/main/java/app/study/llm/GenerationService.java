package app.study.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import app.study.config.OllamaProperties;
import app.study.config.SettingsService;
import app.study.llm.OllamaClient.Message;
import app.study.llm.OllamaClient.Options;

/**
 * Everything the app asks the model to write. Free-form tasks (notes, spoken
 * form, title) stream Markdown/text; structured tasks (key terms, flashcards,
 * quiz) are constrained to a JSON schema and validated before being returned.
 *
 * <p>Long inputs are handled map/reduce style: per-chunk section notes, then
 * one merge pass when the sections fit the budget, otherwise a generated
 * overview and takeaways wrapped around the concatenated sections.
 */
@Service
public class GenerationService {

	private static final Logger log = LoggerFactory.getLogger(GenerationService.class);

	/** Output cap for one section's notes; keeps prompt + reply inside num_ctx. */
	private static final int SECTION_MAX_TOKENS = 2048;

	/** Progress callback: fraction in [0,1] and a human message. */
	@FunctionalInterface
	public interface Progress {
		void report(double fraction, String message);
	}

	private final OllamaClient ollama;
	private final OllamaProperties props;
	private final PromptLoader prompts;
	private final SettingsService settings;
	private final ObjectMapper json;

	private final JsonNode keyTermsSchema;
	private final JsonNode flashcardsSchema;
	private final JsonNode quizSchema;

	public GenerationService(OllamaClient ollama, OllamaProperties props, PromptLoader prompts,
			SettingsService settings, ObjectMapper json) {
		this.ollama = ollama;
		this.props = props;
		this.prompts = prompts;
		this.settings = settings;
		this.json = json;
		this.keyTermsSchema = schema("""
				{"type":"object","required":["terms"],"properties":{"terms":{"type":"array","items":
				 {"type":"object","required":["term","definition"],"properties":
				  {"term":{"type":"string"},"definition":{"type":"string"}}}}}}
				""");
		this.flashcardsSchema = schema("""
				{"type":"object","required":["cards"],"properties":{"cards":{"type":"array","items":
				 {"type":"object","required":["front","back","topic"],"properties":
				  {"front":{"type":"string"},"back":{"type":"string"},"topic":{"type":"string"}}}}}}
				""");
		this.quizSchema = schema("""
				{"type":"object","required":["questions"],"properties":{"questions":{"type":"array","items":
				 {"type":"object","required":["type","topic","question","options","correctIndex","explanation"],
				  "properties":{"type":{"type":"string","enum":["mcq","true_false","fill_blank"]},
				   "topic":{"type":"string"},"question":{"type":"string"},
				   "options":{"type":"array","items":{"type":"string"},"minItems":2,"maxItems":4},
				   "correctIndex":{"type":"integer"},"explanation":{"type":"string"}}}}}}
				""");
	}

	// ---- notes -------------------------------------------------------------

	public String notes(String source, Progress progress) {
		String language = settings.language();
		List<String> chunks = Chunker.chunk(source, props.chunkTokens(), props.overlapTokens());
		if (chunks.isEmpty()) throw new LlmException(LlmException.Kind.BAD_RESPONSE, "There is no text to summarise.");

		if (chunks.size() == 1) {
			progress.report(0.05, "Writing notes…");
			String out = stream(prompts.render("notes-single", Map.of("language", language)),
					"Source material:\n\n" + chunks.get(0), 0.05, 0.95, "Writing notes", progress);
			progress.report(1, "Notes ready");
			return unfence(out);
		}

		int total = chunks.size();
		List<String> sections = new ArrayList<>(total);
		double perChunk = 0.8 / total;
		for (int i = 0; i < total; i++) {
			double from = 0.05 + i * perChunk;
			String system = prompts.render("notes-section",
					Map.of("language", language, "part", i + 1, "total", total));
			String label = "Writing section " + (i + 1) + " of " + total;
			progress.report(from, label + "…");
			sections.add(stripLeadingSummary(unfence(stream(system,
					"Source material (section " + (i + 1) + " of " + total + "):\n\n" + chunks.get(i),
					from, from + perChunk, label, SECTION_MAX_TOKENS, progress))));
		}

		String combined = String.join("\n\n", sections);
		if (Chunker.estimateTokens(combined) <= props.chunkTokens()) {
			progress.report(0.86, "Merging sections…");
			String merged = stream(prompts.render("notes-merge", Map.of("language", language)),
					"Section notes:\n\n" + combined, 0.86, 0.98, "Merging sections", progress);
			progress.report(1, "Notes ready");
			return unfence(merged);
		}

		// Too long to merge in one pass: frame the sections with a generated overview + takeaways.
		progress.report(0.88, "Writing overview and takeaways…");
		String frame = unfence(stream(prompts.render("notes-frame", Map.of("language", language)),
				"Study notes:\n\n" + Chunker.capTokens(combined, props.chunkTokens()),
				0.88, 0.98, "Writing overview", 1024, progress));
		String[] parts = splitFrame(frame);
		StringBuilder sb = new StringBuilder();
		if (!parts[0].isBlank()) sb.append(parts[0].trim()).append("\n\n");
		sb.append(combined.trim());
		if (!parts[1].isBlank()) sb.append("\n\n").append(parts[1].trim());
		progress.report(1, "Notes ready");
		return sb.toString();
	}

	/** Plain-text rewrite of Markdown notes for the TTS voice, chunked if long. */
	public String spoken(String notes, Progress progress) {
		String language = settings.language();
		List<String> chunks = Chunker.chunk(notes, props.chunkTokens(), 0);
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) {
			double from = i / (double) chunks.size();
			String label = chunks.size() > 1 ? "Rewriting part " + (i + 1) + " of " + chunks.size() : "Rewriting for speech";
			progress.report(from, label + "…");
			String part = stream(prompts.render("spoken", Map.of("language", language)),
					"Study notes:\n\n" + chunks.get(i), from, (i + 1) / (double) chunks.size(), label, progress);
			if (out.length() > 0) out.append("\n\n");
			out.append(unfence(part).trim());
		}
		progress.report(1, "Ready to read aloud");
		return out.toString();
	}

	public String title(String material) {
		String reply = ollama.chat(List.of(
				Message.system(prompts.load("title")),
				Message.user("Material:\n\n" + Chunker.capTokens(material, 1500))),
				new Options(settings.chatModel(), 0.3, 40, null)).content();
		String t = reply.strip().replaceAll("^[\"'“”]+|[\"'“”.]+$", "").strip();
		return t.length() > 120 ? t.substring(0, 120) : t;
	}

	// ---- structured --------------------------------------------------------

	public String keyTerms(String grounding) {
		return structured("key-terms", Map.of("language", settings.language()), grounding, keyTermsSchema, "terms");
	}

	public String flashcards(String grounding, int count) {
		return structured("flashcards", Map.of("language", settings.language(), "count", count),
				grounding, flashcardsSchema, "cards");
	}

	public String quiz(String grounding, int count, String difficulty, List<String> types) {
		return structured("quiz", Map.of("language", settings.language(), "count", count,
				"difficulty", difficulty, "types", String.join(", ", types)), grounding, quizSchema, "questions");
	}

	// ---- internals ---------------------------------------------------------

	private String stream(String system, String user, double from, double to, String label, Progress progress) {
		return stream(system, user, from, to, label, null, progress);
	}

	private String stream(String system, String user, double from, double to, String label,
			Integer maxTokens, Progress progress) {
		int[] chars = { 0 };
		long[] lastReport = { 0 };
		String content = ollama.chat(List.of(Message.system(system), Message.user(user)),
				new Options(settings.chatModel(), 0.4, maxTokens, null), delta -> {
					chars[0] += delta.length();
					long now = System.currentTimeMillis();
					if (now - lastReport[0] > 700) {
						lastReport[0] = now;
						// We don't know the final length; creep towards `to` without reaching it.
						double est = Math.min(0.9, chars[0] / 6000.0);
						progress.report(from + (to - from) * est, label + "… (" + (chars[0] / 5) + " words)");
					}
				}).content();
		if (content.isBlank()) {
			throw new LlmException(LlmException.Kind.BAD_RESPONSE, "The model returned an empty reply.");
		}
		return content;
	}

	/**
	 * Schema-constrained call, parsed and checked for the expected top-level
	 * array. Retried once with a sterner instruction if the first reply is
	 * malformed or empty, which small models occasionally produce.
	 */
	private String structured(String promptName, Map<String, ?> vars, String grounding, JsonNode schema, String arrayField) {
		String system = prompts.render(promptName, vars)
				+ "\n\nRespond with a single JSON object matching the required schema. No prose, no Markdown fences.";
		String user = "Study material:\n\n" + Chunker.capTokens(grounding, props.chunkTokens());
		Options opts = new Options(settings.chatModel(), 0.3, null, schema);

		LlmException last = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			String content = ollama.chat(List.of(Message.system(system), Message.user(user)), opts).content();
			try {
				JsonNode node = json.readTree(unfence(content));
				JsonNode arr = node.path(arrayField);
				if (!arr.isArray() || arr.isEmpty()) {
					throw new LlmException(LlmException.Kind.BAD_RESPONSE,
							"The model returned no " + arrayField + ".");
				}
				if ("questions".equals(arrayField)) tidyQuiz(arr);
				return json.writeValueAsString(node);
			} catch (JacksonException e) {
				last = new LlmException(LlmException.Kind.BAD_RESPONSE,
						"The model's reply wasn't valid JSON (" + e.getMessage() + ").", e);
			} catch (LlmException e) {
				last = e;
			}
			log.warn("Structured generation '{}' attempt {} failed: {}", promptName, attempt, last.getMessage());
			system += "\n\nYour previous reply was not usable. Output ONLY the JSON object, with a non-empty \""
					+ arrayField + "\" array.";
		}
		throw last;
	}

	private static final java.util.regex.Pattern OPTION_LINE = java.util.regex.Pattern.compile(
			"(?m)^\\s*\\(?([A-Da-d])[).:]\\s*(.+?)\\s*$");
	private static final java.util.regex.Pattern BARE_LETTER = java.util.regex.Pattern.compile("^\\(?[A-Da-d][).]?$");

	/**
	 * Small models like to restate the answer choices inside the question text
	 * ("...?\nA) ...\nB) ...", sometimes with a literal backslash-n) and then
	 * fill the options array with bare letters. Recover the real options from
	 * those lines when needed, and always drop them from the question.
	 */
	static void tidyQuiz(JsonNode questions) {
		for (JsonNode q : questions) {
			if (!(q instanceof tools.jackson.databind.node.ObjectNode obj)) continue;
			String text = obj.path("question").asText("").replace("\\n", "\n");
			if (!"mcq".equals(obj.path("type").asText())) {
				obj.put("question", text.strip());
				continue;
			}

			List<String> parsed = new ArrayList<>();
			var m = OPTION_LINE.matcher(text);
			while (m.find()) parsed.add(m.group(2).strip());

			JsonNode opts = obj.path("options");
			boolean lettersOnly = opts.isArray() && !opts.isEmpty();
			for (JsonNode o : opts) lettersOnly &= BARE_LETTER.matcher(o.asText("").strip()).matches();
			if (parsed.size() >= 2 && (lettersOnly || opts.size() != parsed.size())) {
				var arr = obj.putArray("options");
				parsed.forEach(arr::add);
			}

			String cleaned = OPTION_LINE.matcher(text).replaceAll("").replaceAll("\\n{2,}", "\n").strip();
			obj.put("question", cleaned.isEmpty() ? text.strip() : cleaned);
		}
	}

	private JsonNode schema(String s) {
		try {
			return json.readTree(s);
		} catch (JacksonException e) {
			throw new IllegalStateException("Bad built-in schema", e);
		}
	}

	private static final java.util.regex.Pattern LEADING_SUMMARY = java.util.regex.Pattern.compile(
			"^\\s*#{1,4}\\s*(summary|overview|introduction|section \\d+[^\\n]*)\\s*\\n(?:(?!#)[^\\n]*\\n?)*",
			java.util.regex.Pattern.CASE_INSENSITIVE);

	/**
	 * Section notes often open with their own "## Summary" paragraph despite the
	 * prompt; the overview is written once for the whole document, so drop it.
	 */
	static String stripLeadingSummary(String section) {
		var m = LEADING_SUMMARY.matcher(section);
		if (!m.find()) return section;
		String rest = section.substring(m.end()).strip();
		return rest.isEmpty() ? section : rest;
	}

	/** Small models sometimes wrap the whole reply in ``` fences despite instructions. */
	static String unfence(String s) {
		String t = s.strip();
		if (t.startsWith("```")) {
			int nl = t.indexOf('\n');
			t = nl >= 0 ? t.substring(nl + 1) : "";
			if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
		}
		return t.strip();
	}

	/** Split the "overview + Key Takeaways" reply into its two halves. */
	static String[] splitFrame(String frame) {
		int idx = frame.toLowerCase().indexOf("## key takeaways");
		if (idx < 0) return new String[] { frame, "" };
		return new String[] { frame.substring(0, idx), frame.substring(idx) };
	}
}
