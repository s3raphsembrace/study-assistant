package app.study.store;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Startup repairs for SQLite databases created by earlier versions:
 * <ul>
 *   <li>rebuilds any table whose DDL still carries a {@code CHECK (...)} clause
 *       (Hibernate pinned enum columns to the values known at creation time;
 *       SQLite has no ALTER for constraints, so it's create-copy-drop-rename),</li>
 *   <li>adds the artifacts (document, kind) unique index, which Hibernate's
 *       update mode silently skips on SQLite.</li>
 * </ul>
 */
@Component
@Order(Integer.MIN_VALUE)
public class SqliteSchemaFixer implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(SqliteSchemaFixer.class);

	private final JdbcTemplate jdbc;

	public SqliteSchemaFixer(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		List<Map<String, Object>> tables = jdbc.queryForList(
				"select name, sql from sqlite_master where type = 'table' and name not like 'sqlite_%'");
		for (Map<String, Object> t : tables) {
			String name = (String) t.get("name");
			String sql = (String) t.get("sql");
			if (sql == null) continue;
			String cleaned = stripChecks(sql);
			if (!cleaned.equals(sql)) rebuild(name, cleaned);
		}
		ensureArtifactUniqueness();
	}

	private void rebuild(String table, String cleanedCreateSql) {
		String tmp = table + "__nocheck";
		String createTmp = cleanedCreateSql.replaceFirst(
				"(?i)create\\s+table\\s+\"?" + Pattern.quote(table) + "\"?", "create table " + tmp);
		List<String> indexes = jdbc.queryForList(
				"select sql from sqlite_master where type = 'index' and tbl_name = ? and sql is not null",
				String.class, table);

		log.info("Rebuilding table {} without CHECK constraints", table);
		jdbc.execute(createTmp);
		jdbc.execute("insert into " + tmp + " select * from " + table);
		jdbc.execute("drop table " + table);
		jdbc.execute("alter table " + tmp + " rename to " + table);
		for (String idx : indexes) jdbc.execute(idx);
	}

	private void ensureArtifactUniqueness() {
		Integer exists = jdbc.queryForObject(
				"select count(*) from sqlite_master where type = 'table' and name = 'artifacts'", Integer.class);
		if (exists == null || exists == 0) return;
		// Keep the newest row per (document, kind) should duplicates have crept in.
		jdbc.update("delete from artifacts where id not in "
				+ "(select max(id) from artifacts group by document_id, kind)");
		jdbc.execute("create unique index if not exists uk_artifacts_document_kind on artifacts (document_id, kind)");
	}

	/**
	 * Remove every {@code [, ] [constraint name] check ( ... )} clause, matching
	 * parentheses at any depth. Package-private for tests.
	 */
	static String stripChecks(String ddl) {
		StringBuilder out = new StringBuilder(ddl);
		String lower;
		int from = 0;
		while (true) {
			lower = out.toString().toLowerCase(Locale.ROOT);
			int at = indexOfWord(lower, "check", from);
			if (at < 0) break;
			int open = lower.indexOf('(', at + 5);
			if (open < 0 || !lower.substring(at + 5, open).isBlank()) {
				from = at + 5;
				continue;
			}
			int close = matchingParen(lower, open);
			if (close < 0) break;

			int start = at;
			// Swallow "constraint <name> " in front of the check, if any.
			int c = lower.lastIndexOf("constraint", at);
			if (c >= 0 && lower.substring(c, at).matches("constraint\\s+\\S+\\s+")) start = c;
			// Table-level checks are separated by a comma; column-level ones by a space.
			int comma = start - 1;
			while (comma >= 0 && Character.isWhitespace(lower.charAt(comma))) comma--;
			if (comma >= 0 && lower.charAt(comma) == ',') start = comma;

			out.delete(start, close + 1);
			from = start;
		}
		return out.toString().replaceAll("\\s+\\)", ")").replaceAll("\\s+,", ",").replaceAll("\\s{2,}", " ");
	}

	private static int indexOfWord(String s, String word, int from) {
		int i = s.indexOf(word, from);
		while (i >= 0) {
			boolean startOk = i == 0 || !Character.isLetterOrDigit(s.charAt(i - 1));
			boolean endOk = i + word.length() >= s.length() || !Character.isLetterOrDigit(s.charAt(i + word.length()));
			if (startOk && endOk) return i;
			i = s.indexOf(word, i + 1);
		}
		return -1;
	}

	private static int matchingParen(String s, int open) {
		int depth = 0;
		boolean inQuote = false;
		for (int i = open; i < s.length(); i++) {
			char ch = s.charAt(i);
			if (ch == '\'') inQuote = !inQuote;
			if (inQuote) continue;
			if (ch == '(') depth++;
			else if (ch == ')' && --depth == 0) return i;
		}
		return -1;
	}
}
