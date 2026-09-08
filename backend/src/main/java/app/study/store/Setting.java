package app.study.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A user preference that survives restarts (chosen model, language, ...). */
@Entity
@Table(name = "settings")
public class Setting {

	@Id
	@Column(name = "setting_key", length = 64)
	private String key;

	@Column(name = "setting_value", columnDefinition = "TEXT")
	private String value;

	protected Setting() {}

	public Setting(String key, String value) {
		this.key = key;
		this.value = value;
	}

	public String getKey() { return key; }
	public String getValue() { return value; }
	public void setValue(String value) { this.value = value; }
}
