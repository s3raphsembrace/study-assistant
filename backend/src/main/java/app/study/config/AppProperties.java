package app.study.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under the {@code app.*} prefix in application.properties.
 *
 * <p>{@code dataDir} is kept as a String because Spring's String-to-Path
 * conversion treats values as resource paths and rejects relative ones such
 * as {@code ../data}.
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String dataDir, String toolsDir, boolean openBrowser) {

	public AppProperties {
		if (dataDir == null || dataDir.isBlank()) dataDir = "./data";
		if (toolsDir == null || toolsDir.isBlank()) toolsDir = "./tools";
	}

	public Path dataPath() {
		return Path.of(dataDir).toAbsolutePath().normalize();
	}

	/** Downloaded binaries and models (Kokoro, whisper, ...). Gitignored. */
	public Path toolsPath() {
		return Path.of(toolsDir).toAbsolutePath().normalize();
	}

	public Path uploadsDir() {
		return dataPath().resolve("uploads");
	}

	public Path audioDir() {
		return dataPath().resolve("audio");
	}
}
