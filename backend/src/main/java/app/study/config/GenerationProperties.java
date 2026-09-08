package app.study.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code app.generation.*}.
 *
 * @param autoNotes       generate notes automatically once a document's text is extracted
 * @param language        language the generated material is written in
 * @param flashcardCount  target number of flashcards
 * @param quizCount       default number of quiz questions
 */
@ConfigurationProperties(prefix = "app.generation")
public record GenerationProperties(boolean autoNotes, String language, int flashcardCount, int quizCount) {

	public GenerationProperties {
		if (language == null || language.isBlank()) language = "English";
		if (flashcardCount <= 0) flashcardCount = 20;
		if (quizCount <= 0) quizCount = 10;
	}
}
