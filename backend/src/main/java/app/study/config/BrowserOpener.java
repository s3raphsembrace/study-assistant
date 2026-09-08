package app.study.config;

import java.awt.Desktop;
import java.net.URI;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Opens the user's default browser at the app once the server is listening.
 * Only when {@code app.open-browser=true} (the packaged "double-click and go"
 * mode); disabled by the {@code dev} profile where you open :4200 instead.
 */
@Component
public class BrowserOpener {

	private static final Logger log = LoggerFactory.getLogger(BrowserOpener.class);

	private final AppProperties props;
	private final Environment env;

	public BrowserOpener(AppProperties props, Environment env) {
		this.props = props;
		this.env = env;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		String url = "http://127.0.0.1:" + env.getProperty("local.server.port", "8080") + "/";
		log.info("Study Assistant is running at {}", url);
		if (!props.openBrowser()) return;
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(URI.create(url));
				return;
			}
		} catch (Exception e) {
			log.debug("java.awt.Desktop browse failed, falling back to a shell command", e);
		}
		try {
			String os = System.getProperty("os.name", "").toLowerCase();
			ProcessBuilder pb = os.contains("win")
					? new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url)
					: os.contains("mac")
							? new ProcessBuilder("open", url)
							: new ProcessBuilder("xdg-open", url);
			pb.inheritIO().start();
		} catch (Exception e) {
			log.warn("Couldn't open a browser automatically — visit {} yourself.", url);
		}
	}
}
