package app.study.api;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import app.study.config.SettingsService;
import app.study.llm.OllamaClient;

/** User preferences plus a live view of the local model runtime. */
@RestController
@RequestMapping("/api/settings")
public class SettingsController {

	public record OllamaState(String baseUrl, boolean reachable, List<String> models) {}

	public record SettingsView(String chatModel, String language, boolean autoNotes, OllamaState ollama) {}

	/** Any field left null is unchanged; an empty string resets it to the default. */
	public record SettingsUpdate(String chatModel, String language, Boolean autoNotes) {}

	private final SettingsService settings;
	private final OllamaClient ollama;

	public SettingsController(SettingsService settings, OllamaClient ollama) {
		this.settings = settings;
		this.ollama = ollama;
	}

	@GetMapping
	public SettingsView get() {
		return view();
	}

	@PutMapping
	public SettingsView update(@RequestBody SettingsUpdate update) {
		if (update.chatModel() != null) settings.set(SettingsService.CHAT_MODEL, update.chatModel());
		if (update.language() != null) settings.set(SettingsService.LANGUAGE, update.language());
		if (update.autoNotes() != null) settings.set(SettingsService.AUTO_NOTES, String.valueOf(update.autoNotes()));
		return view();
	}

	private SettingsView view() {
		boolean up = ollama.isReachable();
		List<String> models = up ? ollama.listModels() : List.of();
		return new SettingsView(settings.chatModel(), settings.language(), settings.autoNotes(),
				new OllamaState(ollama.baseUrl(), up, models));
	}
}
