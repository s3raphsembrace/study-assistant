package app.study.config;

import java.io.IOException;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

/**
 * Serves the built Angular app from {@code classpath:/static/} and falls back to
 * {@code index.html} for client-side routes (e.g. {@code /documents/42}) so a
 * page refresh deep in the app still loads. Anything under {@code /api/} is
 * left alone so unknown API paths return a real 404.
 */
@Configuration
public class SpaConfig implements WebMvcConfigurer {

	@Override
	public void addResourceHandlers(ResourceHandlerRegistry registry) {
		registry.addResourceHandler("/**")
				.addResourceLocations("classpath:/static/")
				.resourceChain(true)
				.addResolver(new PathResourceResolver() {
					@Override
					@Nullable
					protected Resource getResource(String resourcePath, Resource location) throws IOException {
						if (resourcePath.startsWith("api/")) return null;
						Resource requested = location.createRelative(resourcePath);
						if (requested.exists() && requested.isReadable()) return requested;
						Resource index = new ClassPathResource("/static/index.html");
						return index.exists() ? index : null;
					}
				});
	}
}
