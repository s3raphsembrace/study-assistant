package app.study.llm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * Loads prompt templates from {@code classpath:/prompts/<name>.txt} and fills
 * {@code {{placeholders}}}. Templates live as plain files so they can be
 * tweaked without touching Java.
 */
@Component
public class PromptLoader {

	private final Map<String, String> cache = new ConcurrentHashMap<>();

	public String load(String name) {
		return cache.computeIfAbsent(name, n -> {
			ClassPathResource res = new ClassPathResource("prompts/" + n + ".txt");
			try (InputStream in = res.getInputStream()) {
				return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
			} catch (IOException e) {
				throw new IllegalStateException("Missing prompt template: prompts/" + n + ".txt", e);
			}
		});
	}

	public String render(String name, Map<String, ?> vars) {
		String out = load(name);
		for (Map.Entry<String, ?> e : vars.entrySet()) {
			out = out.replace("{{" + e.getKey() + "}}", String.valueOf(e.getValue()));
		}
		return out;
	}
}
