package app.study.config;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;

import app.study.store.Setting;
import app.study.store.SettingRepository;

/**
 * Typed access to persisted user preferences, with the application.properties
 * values as defaults. Reads are served from memory; writes go straight to
 * SQLite.
 */
@Service
public class SettingsService {

	public static final String CHAT_MODEL = "chatModel";
	public static final String LANGUAGE = "language";
	public static final String AUTO_NOTES = "autoNotes";

	private final SettingRepository repo;
	private final OllamaProperties ollama;
	private final GenerationProperties generation;
	private final Map<String, String> cache = new ConcurrentHashMap<>();
	private volatile boolean loaded;

	public SettingsService(SettingRepository repo, OllamaProperties ollama, GenerationProperties generation) {
		this.repo = repo;
		this.ollama = ollama;
		this.generation = generation;
	}

	public String chatModel() {
		return get(CHAT_MODEL).orElse(ollama.chatModel());
	}

	public String language() {
		return get(LANGUAGE).orElse(generation.language());
	}

	public boolean autoNotes() {
		return get(AUTO_NOTES).map(Boolean::parseBoolean).orElse(generation.autoNotes());
	}

	public Optional<String> get(String key) {
		ensureLoaded();
		String v = cache.get(key);
		return v == null || v.isBlank() ? Optional.empty() : Optional.of(v);
	}

	/** Store a value; null or blank clears it so the default applies again. */
	public void set(String key, String value) {
		ensureLoaded();
		if (value == null || value.isBlank()) {
			cache.remove(key);
			repo.deleteById(key);
			return;
		}
		cache.put(key, value);
		repo.save(new Setting(key, value));
	}

	private void ensureLoaded() {
		if (loaded) return;
		synchronized (this) {
			if (loaded) return;
			for (Setting s : repo.findAll()) {
				if (s.getValue() != null) cache.put(s.getKey(), s.getValue());
			}
			loaded = true;
		}
	}
}
