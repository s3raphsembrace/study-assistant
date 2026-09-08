package app.study.ingest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Normalises extracted page text so the LLM and TTS see prose rather than
 * layout noise. Deliberately conservative: it removes only what is almost
 * certainly not content (running headers/footers, bare slide numbers) and
 * repairs hyphenation split across lines.
 */
@Component
public class TextCleaner {

	/** "12", "12 / 40", "Page 12", "12 of 40", "- 12 -". */
	private static final Pattern PAGE_NUMBER = Pattern.compile(
			"^\\s*(?:page\\s*)?-?\\s*\\d{1,4}\\s*(?:(?:/|of)\\s*\\d{1,4})?\\s*-?\\s*$",
			Pattern.CASE_INSENSITIVE);

	/** Word broken across a line with a hyphen: "informa-\ntion" -> "information". */
	private static final Pattern SOFT_HYPHEN = Pattern.compile("(\\p{L})-\\n(\\p{Ll})");

	private static final Pattern BULLET = Pattern.compile("^[\\s]*[•·◦▪▫■□●○‣⁃-]\\s*");

	/** Minimum number of pages before repeated-line detection kicks in. */
	private static final int MIN_PAGES_FOR_REPEATS = 4;
	/** A short line that appears on at least this fraction of pages is a header/footer. */
	private static final double REPEAT_FRACTION = 0.5;
	private static final int REPEAT_MAX_LEN = 80;

	public List<String> cleanPages(List<String> rawPages) {
		List<List<String>> lines = new ArrayList<>(rawPages.size());
		for (String raw : rawPages) lines.add(splitLines(raw));

		Set<String> repeated = findRepeatedLines(lines);

		List<String> out = new ArrayList<>(rawPages.size());
		for (List<String> page : lines) {
			StringBuilder sb = new StringBuilder();
			for (String line : page) {
				String key = normaliseKey(line);
				if (key.isEmpty()) {
					// Keep at most one blank line: paragraph breaks matter for chunking.
					if (sb.length() > 0 && !endsWithBlankLine(sb)) sb.append('\n');
					continue;
				}
				if (repeated.contains(key)) continue;
				if (PAGE_NUMBER.matcher(line).matches()) continue;
				sb.append(BULLET.matcher(line).replaceFirst("- ")).append('\n');
			}
			out.add(dedupeSentences(finish(sb.toString())));
		}
		return out;
	}

	private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+");
	private static final int DEDUPE_MIN_LEN = 40;

	/**
	 * Some PDFs carry two overlapping text layers (a watermarked draft under the
	 * final page, say), so every sentence comes out twice. Drop repeats of any
	 * reasonably long sentence within one page; short ones are left alone since
	 * they can legitimately recur.
	 */
	static String dedupeSentences(String page) {
		Set<String> seen = new HashSet<>();
		StringBuilder out = new StringBuilder();
		for (String para : page.split("\\n{2,}")) {
			StringBuilder kept = new StringBuilder();
			for (String line : para.split("\n")) {
				StringBuilder keptLine = new StringBuilder();
				for (String s : SENTENCE_END.split(line)) {
					String key = s.replaceAll("\\s+", " ").trim().toLowerCase();
					if (key.length() >= DEDUPE_MIN_LEN && !seen.add(key)) continue;
					if (keptLine.length() > 0) keptLine.append(' ');
					keptLine.append(s.trim());
				}
				if (keptLine.length() == 0) continue;
				if (kept.length() > 0) kept.append('\n');
				kept.append(keptLine);
			}
			if (kept.length() == 0) continue;
			if (out.length() > 0) out.append("\n\n");
			out.append(kept);
		}
		return out.toString();
	}

	private static final Pattern REFERENCES_HEADING = Pattern.compile(
			"(?im)^\\s*(?:\\d+\\.?\\s*)?(references|bibliography|works cited|literature cited)\\s*:?\\s*$");

	/**
	 * Drop a trailing bibliography: everything from a "References" heading that
	 * sits in the last half of the text. Papers carry pages of citations that
	 * only add noise to notes and take minutes to summarise.
	 */
	public String stripReferences(String text) {
		var m = REFERENCES_HEADING.matcher(text);
		int cut = -1;
		while (m.find()) cut = m.start(); // the last such heading
		if (cut < 0 || cut < text.length() / 2) return text;
		return text.substring(0, cut).trim();
	}

	public String joinPages(List<String> pages) {
		StringBuilder sb = new StringBuilder();
		for (String p : pages) {
			if (p.isBlank()) continue;
			if (sb.length() > 0) sb.append("\n\n");
			sb.append(p.trim());
		}
		return sb.toString();
	}

	private static List<String> splitLines(String raw) {
		String norm = raw.replace("\r\n", "\n").replace('\r', '\n');
		norm = SOFT_HYPHEN.matcher(norm).replaceAll("$1$2");
		List<String> out = new ArrayList<>();
		for (String l : norm.split("\n")) out.add(l.replaceAll("[ \\t\\u00A0]+", " ").trim());
		return out;
	}

	private static Set<String> findRepeatedLines(List<List<String>> pages) {
		Set<String> repeated = new HashSet<>();
		if (pages.size() < MIN_PAGES_FOR_REPEATS) return repeated;

		Map<String, Integer> pagesContaining = new HashMap<>();
		for (List<String> page : pages) {
			Set<String> seen = new HashSet<>();
			for (String line : page) {
				String key = normaliseKey(line);
				if (key.isEmpty() || key.length() > REPEAT_MAX_LEN) continue;
				if (seen.add(key)) pagesContaining.merge(key, 1, Integer::sum);
			}
		}
		int threshold = (int) Math.ceil(pages.size() * REPEAT_FRACTION);
		for (Map.Entry<String, Integer> e : pagesContaining.entrySet()) {
			if (e.getValue() >= threshold) repeated.add(e.getKey());
		}
		return repeated;
	}

	/** Case-folded, digits collapsed so "Lecture 3 · Slide 12" and "... Slide 13" match. */
	private static String normaliseKey(String line) {
		return line.toLowerCase().replaceAll("\\d+", "#").replaceAll("\\s+", " ").trim();
	}

	private static boolean endsWithBlankLine(StringBuilder sb) {
		int n = sb.length();
		return n >= 2 && sb.charAt(n - 1) == '\n' && sb.charAt(n - 2) == '\n';
	}

	private static String finish(String text) {
		return text.replaceAll("\\n{3,}", "\n\n").trim();
	}
}
