package app.study.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SqliteSchemaFixerTest {

	@Test
	void stripsNestedColumnChecks() {
		String ddl = "CREATE TABLE artifacts (id integer, kind varchar(255) not null "
				+ "check ((kind in ('NOTES','KEY_TERMS','QUIZ'))), model varchar(255), primary key (id))";
		assertThat(SqliteSchemaFixer.stripChecks(ddl)).isEqualTo(
				"CREATE TABLE artifacts (id integer, kind varchar(255) not null, model varchar(255), primary key (id))");
	}

	@Test
	void stripsTableLevelNamedChecks() {
		String ddl = "create table t (a int, b int, constraint ck_b check (b in (1,2)), primary key (a))";
		assertThat(SqliteSchemaFixer.stripChecks(ddl))
				.isEqualTo("create table t (a int, b int, primary key (a))");
	}

	@Test
	void leavesDdlWithoutChecksAlone() {
		String ddl = "create table settings (setting_key varchar(64) not null, setting_value TEXT, primary key (setting_key))";
		assertThat(SqliteSchemaFixer.stripChecks(ddl)).isEqualTo(ddl);
	}
}
