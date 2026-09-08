package app.study.llm;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups whole pages into generation-sized sections so each section of the
 * notes knows which pages (slides) it came from. A single page that exceeds
 * the budget on its own is split with {@link Chunker}, every piece keeping
 * that page number.
 */
public final class PageChunker {

	/** Text of consecutive pages {@code firstPage..lastPage} (1-based, inclusive). */
	public record Section(String text, int firstPage, int lastPage) {
		public String pageRange() {
			return firstPage == lastPage ? String.valueOf(firstPage) : firstPage + "-" + lastPage;
		}
	}

	private PageChunker() {}

	/**
	 * @param pages     page texts in order (blank pages allowed)
	 * @param maxTokens token budget per section
	 * @param maxPages  cap on pages per section, so slide decks get a strip of
	 *                  a handful of slides per section rather than one huge one;
	 *                  0 for no cap
	 */
	public static List<Section> chunk(List<String> pages, int maxTokens, int maxPages) {
		List<Section> out = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		int curFirst = -1;
		int curLast = -1;
		int curPages = 0;

		for (int i = 0; i < pages.size(); i++) {
			int pageNo = i + 1;
			String text = pages.get(i) == null ? "" : pages.get(i).trim();
			if (text.isEmpty()) continue;

			if (Chunker.estimateTokens(text) > maxTokens) {
				// Flush what we have, then emit the oversized page in pieces.
				if (cur.length() > 0) {
					out.add(new Section(cur.toString(), curFirst, curLast));
					cur.setLength(0);
					curPages = 0;
				}
				for (String piece : Chunker.chunk(text, maxTokens, 0)) {
					out.add(new Section(piece, pageNo, pageNo));
				}
				continue;
			}

			boolean overBudget = cur.length() > 0
					&& Chunker.estimateTokens(cur.toString()) + Chunker.estimateTokens(text) > maxTokens;
			boolean overPages = maxPages > 0 && curPages >= maxPages;
			if (overBudget || overPages) {
				out.add(new Section(cur.toString(), curFirst, curLast));
				cur.setLength(0);
				curPages = 0;
			}
			if (cur.length() == 0) curFirst = pageNo;
			else cur.append("\n\n");
			cur.append(text);
			curLast = pageNo;
			curPages++;
		}
		if (cur.length() > 0) out.add(new Section(cur.toString(), curFirst, curLast));
		return out;
	}
}
