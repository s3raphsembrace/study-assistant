package app.study.jobs;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;

import app.study.config.AppProperties;
import app.study.config.GenerationProperties;
import app.study.tts.TtsService;
import app.study.config.SettingsService;
import app.study.ingest.TextCleaner;
import app.study.llm.GenerationService;
import app.study.llm.LlmException;
import app.study.store.Artifact;
import app.study.store.ArtifactRepository;
import app.study.store.Document;
import app.study.store.DocumentPage;
import app.study.store.DocumentPageRepository;
import app.study.store.DocumentRepository;

/**
 * Produces one artifact for one document in the background and stores it.
 * Structured kinds are grounded on the notes when they exist (shorter and
 * already synthesised), falling back to the raw text.
 */
@Service
public class GenerationPipeline {

	private static final Logger log = LoggerFactory.getLogger(GenerationPipeline.class);

	/** Extra options for the on-demand kinds. Nulls mean defaults. */
	public record Request(Integer count, String difficulty, List<String> types, String voice, Double speed) {
		public static final Request DEFAULTS = new Request(null, null, null, null, null);
	}

	private final DocumentRepository documents;
	private final DocumentPageRepository pages;
	private final ArtifactRepository artifacts;
	private final GenerationService generator;
	private final GenerationProperties props;
	private final SettingsService settings;
	private final TextCleaner cleaner;
	private final JobService jobs;
	private final TtsService tts;
	private final AppProperties app;
	private final ObjectMapper json;

	public GenerationPipeline(DocumentRepository documents, DocumentPageRepository pages,
			ArtifactRepository artifacts, GenerationService generator, GenerationProperties props,
			SettingsService settings, TextCleaner cleaner, JobService jobs, TtsService tts,
			AppProperties app, ObjectMapper json) {
		this.documents = documents;
		this.pages = pages;
		this.artifacts = artifacts;
		this.generator = generator;
		this.props = props;
		this.settings = settings;
		this.cleaner = cleaner;
		this.jobs = jobs;
		this.tts = tts;
		this.app = app;
		this.json = json;
	}

	/** Fire-and-forget entry point used by the API. */
	@Async
	public void start(Long documentId, Artifact.Kind kind, Request request, String jobId) {
		try {
			run(documentId, kind, request, jobId);
			jobs.done(jobId, label(kind) + " ready");
		} catch (Exception e) {
			jobs.fail(jobId, userMessage(e));
		}
	}

	/**
	 * Blocking variant for callers that own the job (the ingest pipeline chains
	 * notes generation onto extraction). Throws on failure; does not complete the job.
	 */
	public void run(Long documentId, Artifact.Kind kind, Request request, String jobId) {
		Document doc = documents.findById(documentId)
				.orElseThrow(() -> new IllegalStateException("Document " + documentId + " no longer exists."));
		String stage = kind.name().toLowerCase();
		jobs.progress(jobId, stage, 0.0, "Preparing " + label(kind).toLowerCase() + "…");

		String content = switch (kind) {
			case NOTES -> generator.notes(sourcePages(documentId),
					doc.getSourceKind() == Document.SourceKind.PDF,
					(f, m) -> jobs.progress(jobId, stage, f, m));
			case SPOKEN -> generator.spoken(notesOrSource(documentId),
					(f, m) -> jobs.progress(jobId, stage, f, m));
			case KEY_TERMS -> {
				jobs.progress(jobId, stage, 0.2, "Extracting key terms…");
				yield generator.keyTerms(notesOrSource(documentId));
			}
			case FLASHCARDS -> {
				jobs.progress(jobId, stage, 0.2, "Writing flashcards…");
				int count = request.count() != null ? request.count() : props.flashcardCount();
				yield generator.flashcards(notesOrSource(documentId), count);
			}
			case QUIZ -> {
				jobs.progress(jobId, stage, 0.2, "Writing quiz questions…");
				int count = request.count() != null ? request.count() : props.quizCount();
				String difficulty = request.difficulty() != null ? request.difficulty() : "intermediate";
				List<String> types = request.types() != null && !request.types().isEmpty()
						? request.types() : List.of("mcq", "true_false");
				yield generator.quiz(notesOrSource(documentId), count, difficulty, types);
			}
			case AUDIO -> {
				String text = spokenText(documentId, jobId);
				String voice = request.voice() != null && !request.voice().isBlank() ? request.voice() : tts.defaultVoice();
				double speed = request.speed() != null ? request.speed() : 1.0;
				jobs.progress(jobId, "audio", 0.3, "Synthesizing speech…");
				Path out = app.audioDir().resolve("notes-" + documentId + ".wav");
				TtsService.Result r;
				try {
					r = tts.synthesizeToFile(text, voice, speed, out,
							p -> jobs.progress(jobId, "audio", 0.3 + 0.7 * p,
									"Synthesizing speech… " + Math.round(p * 100) + "%"));
				} catch (IOException e) {
					throw new IllegalStateException(e.getMessage(), e);
				}
				yield json.writeValueAsString(Map.of(
						"path", r.file().toString(),
						"voice", voice,
						"speed", speed,
						"sampleRate", r.sampleRate(),
						"bytes", r.bytes(),
						"seconds", r.seconds()));
			}
		};

		String model = kind == Artifact.Kind.AUDIO ? "kokoro" : settings.chatModel();
		Artifact existing = artifacts.findByDocumentIdAndKind(documentId, kind).orElse(null);
		if (existing != null) {
			existing.replace(content, model);
			artifacts.save(existing);
		} else {
			artifacts.save(new Artifact(documentId, kind, content, model));
		}

		if (kind == Artifact.Kind.NOTES) maybeImproveTitle(doc, GenerationService.stripSlideRefs(content));
		log.info("Generated {} for document {} with {}", kind, documentId, model);
	}

	/** Cleaned page texts with any trailing bibliography removed; index 0 is page 1. */
	private List<String> sourcePages(Long documentId) {
		List<String> list = cleaner.stripReferences(
				pages.findByDocumentIdOrderByPageNumber(documentId).stream().map(DocumentPage::getText).toList());
		if (list.stream().allMatch(p -> p == null || p.isBlank())) {
			throw new IllegalStateException("This document has no extracted text yet.");
		}
		return list;
	}

	private String sourceText(Long documentId) {
		return cleaner.joinPages(sourcePages(documentId));
	}

	/**
	 * Text for the voice: the SPOKEN artifact, generated first (inside this job)
	 * when it doesn't exist yet. Falls back to the raw notes if there are no notes
	 * to rewrite either.
	 */
	private String spokenText(Long documentId, String jobId) {
		Optional<Artifact> spoken = artifacts.findByDocumentIdAndKind(documentId, Artifact.Kind.SPOKEN);
		if (spoken.isPresent() && !spoken.get().getContent().isBlank()) return spoken.get().getContent();
		Optional<Artifact> notes = artifacts.findByDocumentIdAndKind(documentId, Artifact.Kind.NOTES);
		if (notes.isEmpty()) throw new IllegalStateException("Generate the notes first; the audio reads them.");
		String text = generator.spoken(GenerationService.stripSlideRefs(notes.get().getContent()),
				(f, m) -> jobs.progress(jobId, "spoken", f * 0.3, m));
		artifacts.save(new Artifact(documentId, Artifact.Kind.SPOKEN, text, settings.chatModel()));
		return text;
	}

	private String notesOrSource(Long documentId) {
		return artifacts.findByDocumentIdAndKind(documentId, Artifact.Kind.NOTES)
				.map(Artifact::getContent)
				.map(GenerationService::stripSlideRefs)
				.filter(c -> !c.isBlank())
				.orElseGet(() -> sourceText(documentId));
	}

	/** Filenames like "lec07_final.pdf" make poor titles; ask the model once notes exist. */
	private void maybeImproveTitle(Document doc, String notes) {
		if (!doc.getTitle().equals(Titles.fromFilename(doc.getFilename()))) return; // metadata or user title
		try {
			String t = generator.title(notes);
			if (t != null && t.length() >= 4) {
				doc.setTitle(t);
				documents.save(doc);
			}
		} catch (Exception e) {
			log.debug("Title generation skipped: {}", e.getMessage());
		}
	}

	static String label(Artifact.Kind kind) {
		return switch (kind) {
			case NOTES -> "Notes";
			case KEY_TERMS -> "Key terms";
			case FLASHCARDS -> "Flashcards";
			case QUIZ -> "Quiz";
			case SPOKEN -> "Spoken version";
			case AUDIO -> "Audio";
		};
	}

	static String userMessage(Exception e) {
		if (e instanceof LlmException || e instanceof IllegalStateException) return e.getMessage();
		log.warn("Generation failed", e);
		return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
	}
}
