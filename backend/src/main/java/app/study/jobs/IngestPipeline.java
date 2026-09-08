package app.study.jobs;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import app.study.config.SettingsService;
import app.study.ingest.PageRenderer;
import app.study.ingest.PdfExtractor;
import app.study.ingest.TextCleaner;
import app.study.store.Artifact;
import app.study.store.Document;
import app.study.store.DocumentPage;
import app.study.store.DocumentPageRepository;
import app.study.store.DocumentRepository;

/**
 * Background pipeline for one document: extract -> clean -> persist pages.
 * Generation (notes, flashcards, ...) will hang off the end of this in Phase 2.
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

	public IngestPipeline(DocumentRepository documents, DocumentPageRepository pages,
			PdfExtractor pdf, TextCleaner cleaner, JobService jobs, GenerationPipeline generation,
			SettingsService settings, PageRenderer renderer) {
		this.documents = documents;
		this.pages = pages;
		this.pdf = pdf;
		this.cleaner = cleaner;
		this.jobs = jobs;
		this.generation = generation;
		this.settings = settings;
		this.renderer = renderer;
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

			int chars = 0;
			List<DocumentPage> rows = new ArrayList<>(cleaned.size());
			for (int i = 0; i < cleaned.size(); i++) {
				String t = cleaned.get(i);
				chars += t.length();
				rows.add(new DocumentPage(documentId, i + 1, t));
			}
			pages.deleteByDocumentId(documentId);
			pages.saveAll(rows);

			if (chars == 0) {
				throw new IllegalStateException(
						"No text found. This PDF is probably scanned images; OCR isn't enabled yet.");
			}

			// Slide images for the notes. Best effort: a rendering failure shouldn't lose the text.
			try {
				int total = ex.pageCount();
				renderer.renderAll(documentId, file, p ->
						jobs.progress(jobId, "render", 0.85 + 0.13 * p / Math.max(1, total),
								"Rendering slide " + p + " of " + total + "\u2026"));
			} catch (Exception e) {
				log.warn("Page images for document {} couldn't be rendered: {}", documentId, e.toString());
			}

			if (ex.metadataTitle() != null && looksLikeRealTitle(ex.metadataTitle(), doc.getFilename())) {
				doc.setTitle(ex.metadataTitle());
			}
			doc.setPageCount(ex.pageCount());
			doc.setCharCount(chars);
			doc.setStatus(Document.Status.READY);
			doc.setError(null);
			documents.save(doc);
			jobs.done(jobId, "Extracted " + ex.pageCount() + " pages");
			log.info("Ingested document {} ({} pages, {} chars)", documentId, ex.pageCount(), chars);

			if (settings.autoNotes()) {
				// Notes get their own job so the document view can follow it; the
				// ingest job above is already complete from the library's point of view.
				JobService.JobView notesJob = jobs.create(documentId, Artifact.Kind.NOTES.name(), doc.getTitle());
				generation.start(documentId, Artifact.Kind.NOTES, GenerationPipeline.Request.DEFAULTS, notesJob.id());
			}
		} catch (Exception e) {
			log.warn("Ingest failed for document {}", documentId, e);
			doc.setStatus(Document.Status.ERROR);
			doc.setError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
			documents.save(doc);
			jobs.fail(jobId, doc.getError());
		}
	}

	/** PDF metadata titles are often junk like "PowerPoint Presentation" or "Slide 1". */
	private static boolean looksLikeRealTitle(String title, String filename) {
		String t = title.toLowerCase();
		if (t.length() < 4 || t.length() > 120) return false;
		if (t.contains("powerpoint") || t.contains("presentation") || t.startsWith("slide ")) return false;
		if (t.endsWith(".pptx") || t.endsWith(".docx") || t.endsWith(".pdf")) return false;
		if (t.equals("untitled") || t.equals("document")) return false;
		return true;
	}
}
