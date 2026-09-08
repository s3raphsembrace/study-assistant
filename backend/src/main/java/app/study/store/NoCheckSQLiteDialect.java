package app.study.store;

import org.hibernate.community.dialect.SQLiteDialect;

/**
 * SQLite dialect without column CHECK constraints. Hibernate otherwise pins
 * every {@code @Enumerated} column to the enum values known at table-creation
 * time, and SQLite can't alter a constraint later, so adding an enum value
 * (a new artifact kind, say) would break inserts on existing databases.
 * Validation of enum values happens in Java anyway.
 */
public class NoCheckSQLiteDialect extends SQLiteDialect {

	@Override
	public boolean supportsColumnCheck() {
		return false;
	}

	@Override
	public boolean supportsTableCheck() {
		return false;
	}
}
