package app.study.jobs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import app.study.config.AppProperties;
import app.study.config.SettingsService;
import app.study.ingest.PageRenderer;
import app.study.ingest.PdfExtractor;
import app.study.ingest.TextCleaner;
import app.study.media.Transcriber;
import app.study.media.YoutubeExtractor;
import app.study.store.Artifact;
import app.study.store.Document;
import app.study.store.DocumentPage;
import app.study.store.DocumentPageRepository;
import app.study.store.DocumentRepository;

/**
 * Background ingestion for one document: get its text (PDF extraction, local
 * speech-to-text, or YouTube captions), store it as pages, then hand off to
 * notes generation. Every source kind ends in the same shape, so everything
 * downstream is unaware of where the text came from.
 */
@Service
public class IngestPipeline {

	private static final Logger log = LoggerFactory.getLogger(IngestPipeline.class);

	private final DocumentRepository documents;
	private final DocumentPageRepository pages;
	private final PdfExtractor pdf;
	private final TextCleaner cleaner;
	private final JobService jobs;
	private final GenerationPipeline generation;
	private final SettingsService settings;
	private final PageRenderer renderer;
	private final Transcriber transcriber;
	private final YoutubeExtractor youtube;
	private final AppProperties app;

	public IngestPipeline(DocumentRepository documents, DocumentPageRepository pages,
			PdfExtractor pdf, TextCleaner cleaner, JobService jobs, GenerationPipeline generation,
			SettingsService settings, PageRenderer renderer, Transcriber transcriber,
			YoutubeExtractor youtube, AppProperties app) {
		this.documents = documents;
		this.pages = pages;
		this.pdf = pdf;
		this.cleaner = cleaner;
		this.jobs = jobs;
		this.generation = generation;
		this.settings = settings;
		this.renderer = renderer;
		this.transcriber = transcriber;
		this.youtube = youtube;
		this.app = app;
	}

	@Async
	public void ingestPdf(Long documentId, String jobId) {
		Document doc = documents.findById(documentId).orElse(null);
		if (doc == null) {
			jobs.fail(jobId, "Document " + documentId + " no longer exists.");
			return;
		}
		try {
			doc.setStatus(Document.Status.EXTRACTING);
			documents.save(doc);
			jobs.progress(jobId, "extract", 0.05, "Opening PDF…");

			Path file = Path.of(doc.getStoredPath());
			PdfExtractor.Extraction ex = pdf.extract(file, (p, total) ->
					jobs.progress(jobId, "extract", 0.05 + 0.7 * p / Math.max(1, total),
							"Reading page " + p + " of " + total + "…"));

			jobs.progress(jobId, "clean", 0.8, "Cleaning up text…");
			List<String> cleaned = cleaner.cleanPages(ex.pages());
			int chars = savePages(documentId, cleaned);

			if (chars == 0) {
				throw new IllegalStateException(
						"No text found. This PDF is probably scanned images; OCR isn't enabled yet.");
			}

			// Slide images for the notes. Best effort: a rendering failure shouldn't lose the text.
			try {
				int total = ex.pageCount();
				renderer.renderAll(documentId, file, p ->
						jobs.progress(jobId, "render", 0.85 + 0.13 * p / Math.max(1, total),
								"Rendering slide " + p + " of " + total + "…"));
			} catch (Exception e) {
				log.warn("Page images for document {} couldn't be rendered: {}", documentId, e.toString());
			}

			if (ex.metadataTitle() != null && looksLikeRealTitle(ex.metadataTitle())) {
				doc.setTitle(ex.metadataTitle());
			}
			doc.setPageCount(ex.pageCount());
			finish(doc, jobId, chars, "Extracted " + ex.pageCount() + " pages");
		} catch (Exception e) {
			fail(doc, jobId, e);
		}
	}

	/**
	 * An uploaded audio or video file: decode with ffmpeg, transcribe with
	 * whisper.cpp, store the transcript in timestamped parts.
	 */
	@Async
	public void ingestMedia(Long documentId, String jobId) {
		Document doc = documents.findById(documentId).orElse(null);
		if (doc == null) {
			jobs.fail(jobId, "Document " + documentId + " no longer exists.");
			return;
		}
		Path work = workDir(documentId);
		try {
			doc.setStatus(Document.Status.EXTRACTING);
			documents.save(doc);

			Files.createDirectories(work);
			Path wav = work.resolve("audio.wav");
			jobs.progress(jobId, "decode", 0.02, "Decoding audio…");
			double duration = transcriber.toWav(Path.of(doc.getStoredPath()), wav,
					(f, m) -> jobs.progress(jobId, "decode", 0.02 + 0.08 * f, m));

			int chars = transcribeInto(documentId, doc, jobId, wav, duration);
			finish(doc, jobId, chars, "Transcribed " + Transcriber.clock(doc.getDurationSeconds() == null
					? duration : doc.getDurationSeconds()));
		} catch (Exception e) {
			fail(doc, jobId, e);
		} finally {
			deleteQuietly(work);
		}
	}

	/** A YouTube link: captions when the video has them, otherwise its audio through whisper. */
	@Async
	public void ingestYoutube(Long documentId, String jobId) {
		Document doc = documents.findById(documentId).orElse(null);
		if (doc == null) {
			jobs.fail(jobId, "Document " + documentId + " no longer exists.");
			return;
		}
		Path work = workDir(documentId);
		try {
			doc.setStatus(Document.Status.EXTRACTING);
			documents.save(doc);

			YoutubeExtractor.Extraction ex = youtube.extract(doc.getSourceUrl(), work,
					(f, m) -> jobs.progress(jobId, "download", f, m));
			if (ex.title() != null && !ex.title().isBlank()) doc.setTitle(trimTitle(ex.title()));
			if (ex.durationSeconds() > 0) doc.setDurationSeconds(ex.durationSeconds());

			int chars;
			String message;
			if (ex.captions() != null) {
				jobs.progress(jobId, "captions", 0.5, "Reading captions…");
				chars = savePages(documentId, splitCaptions(ex.captions()));
				message = "Used the video's captions";
			} else {
				chars = transcribeInto(documentId, doc, jobId, ex.audio(), ex.durationSeconds());
				message = "Transcribed " + Transcriber.clock(ex.durationSeconds());
			}
			finish(doc, jobId, chars, message);
		} catch (Exception e) {
			fail(doc, jobId, e);
		} finally {
			deleteQuietly(work);
		}
	}

	// ---- shared steps ------------------------------------------------------

	private int transcribeInto(Long documentId, Document doc, String jobId, Path wav, double duration)
			throws IOException {
		jobs.progress(jobId, "transcribe", 0.12,
				duration > 0 ? "Transcribing " + Transcriber.clock(duration) + " of audio…" : "Transcribing…");
		Transcriber.Result result = transcriber.transcribe(wav, duration,
				(f, m) -> jobs.progress(jobId, "transcribe", 0.12 + 0.85 * f, m));
		doc.setDurationSeconds(result.durationSeconds());
		return savePages(documentId, transcriber.toParts(result.segments()));
	}

	private int savePages(Long documentId, List<String> parts) {
		int chars = 0;
		List<DocumentPage> rows = new ArrayList<>(parts.size());
		for (int i = 0; i < parts.size(); i++) {
			String t = parts.get(i) == null ? "" : parts.get(i);
			chars += t.length();
			rows.add(new DocumentPage(documentId, i + 1, t));
		}
		pages.deleteByDocumentId(documentId);
		pages.saveAll(rows);
		return chars;
	}

	private void finish(Document doc, String jobId, int chars, String message) {
		if (chars == 0) throw new IllegalStateException("No text could be extracted from this source.");
		doc.setCharCount(chars);
		if (doc.getPageCount() == null) {
			doc.setPageCount((int) pages.findByDocumentIdOrderByPageNumber(doc.getId()).stream().count());
		}
		doc.setStatus(Document.Status.READY);
		doc.setError(null);
		documents.save(doc);
		jobs.done(jobId, message);
		log.info("Ingested document {} ({}, {} chars)", doc.getId(), doc.getSourceKind(), chars);

		if (settings.autoNotes()) {
			// Notes get their own job so the document view can follow it; the
			// ingest job above is already complete from the library's point of view.
			JobService.JobView notesJob = jobs.create(doc.getId(), Artifact.Kind.NOTES.name(), doc.getTitle());
			generation.start(doc.getId(), Artifact.Kind.NOTES, GenerationPipeline.Request.DEFAULTS, notesJob.id());
		}
	}

	private void fail(Document doc, String jobId, Exception e) {
		log.warn("Ingest failed for document {}", doc.getId(), e);
		doc.setStatus(Document.Status.ERROR);
		doc.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
		documents.save(doc);
		jobs.fail(jobId, doc.getError());
	}

	private Path workDir(Long documentId) {
		return app.dataPath().resolve("work").resolve(String.valueOf(documentId));
	}

	private static void deleteQuietly(Path dir) {
		if (!Files.isDirectory(dir)) return;
		try (Stream<Path> walk = Files.walk(dir)) {
			walk.sorted(Comparator.reverseOrder()).forEach(p -> {
				try {
					Files.deleteIfExists(p);
				} catch (IOException ignored) {
					/* the OS may still hold a handle; harmless */
				}
			});
		} catch (IOException e) {
			log.debug("Couldn't clean {}", dir);
		}
	}

	/** Captions arrive as one long line; break them into readable parts for the source view. */
	private static List<String> splitCaptions(String text) {
		List<String> parts = new ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String sentence : text.split("(?<=[.!?])\\s+")) {
			if (cur.length() > 0 && cur.length() + sentence.length() > 1500) {
				parts.add(cur.toString());
				cur.setLength(0);
			}
			if (cur.length() > 0) cur.append(' ');
			cur.append(sentence);
		}
		if (cur.length() > 0) parts.add(cur.toString());
		return parts;
	}

	private static String trimTitle(String t) {
		String s = t.strip();
		return s.length() > 120 ? s.substring(0, 120) : s;
	}

	/** PDF metadata titles are often junk like "PowerPoint Presentation" or "Slide 1". */
	private static boolean looksLikeRealTitle(String title) {
		String t = title.toLowerCase();
		if (t.length() < 4 || t.length() > 120) return false;
		if (t.contains("powerpoint") || t.contains("presentation") || t.startsWith("slide ")) return false;
		if (t.endsWith(".pptx") || t.endsWith(".docx") || t.endsWith(".pdf")) return false;
		if (t.equals("untitled") || t.equals("document")) return false;
		return true;
	}
}
