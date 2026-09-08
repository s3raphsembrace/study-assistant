package app.study;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ApplicationListener;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(BackendApplication.class);
		// sqlite-jdbc creates the .db file but not its parent directory, and the
		// datasource initialises before any bean of ours runs. Once the
		// environment (properties, profiles, CLI args) is resolved, make sure
		// the data dir exists.
		app.addListeners((ApplicationListener<ApplicationEnvironmentPreparedEvent>) event -> {
			String dir = event.getEnvironment().getProperty("app.data-dir", "./data");
			try {
				Files.createDirectories(Path.of(dir));
			} catch (IOException e) {
				throw new UncheckedIOException("Couldn't create data directory " + dir, e);
			}
		});
		app.run(args);
	}
}
