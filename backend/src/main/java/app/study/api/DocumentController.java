package app.study.api;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import app.study.config.AppProperties;
import app.study.ingest.TextCleaner;
import app.study.jobs.IngestPipeline;
import app.study.jobs.JobService;
import app.study.jobs.Titles;
import app.study.store.ArtifactRepository;
import app.study.store.Document;
import app.study.store.DocumentPage;
import app.study.store.DocumentPageRepository;
import app.study.store.DocumentRepository;

/** Upload, list, read and delete study documents. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

	private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

	public record DocumentSummary(
			Long id, String title, String filename, Document.SourceKind sourceKind,
			Document.Status status, Integer pageCount, Integer charCount, String error,
			Instant createdAt, Instant updatedAt) {

		static DocumentSummary of(Document d) {
			return new DocumentSummary(d.getId(), d.getTitle(), d.getFilename(), d.getSourceKind(),
					d.getStatus(), d.getPageCount(), d.getCharCount(), d.getError(),
					d.getCreatedAt(), d.getUpdatedAt());
		}
	}

	public record PageView(int pageNumber, String text) {}

	public record UploadResult(DocumentSummary document, JobService.JobView job) {}

	private final DocumentRepository documents;
	private final DocumentPageRepository pages;
	private final ArtifactRepository artifacts;
	private final IngestPipeline pipeline;
	private final JobService jobs;
	private final TextCleaner cleaner;
	private final AppProperties props;

	public DocumentController(DocumentRepository documents, DocumentPageRepository pages,
			ArtifactRepository artifacts, IngestPipeline pipeline, JobService jobs, TextCleaner cleaner,
			AppProperties props) {
		this.documents = documents;
		this.pages = pages;
		this.artifacts = artifacts;
		this.pipeline = pipeline;
		this.jobs = jobs;
		this.cleaner = cleaner;
		this.props = props;
	}

	@GetMapping
	public List<DocumentSummary> list() {
		return documents.findAllByOrderByCreatedAtDesc().stream().map(DocumentSummary::of).toList();
	}

	@GetMapping("/{id}")
	public DocumentSummary get(@PathVariable Long id) {
		return DocumentSummary.of(find(id));
	}

	@GetMapping("/{id}/pages")
	public List<PageView> pages(@PathVariable Long id) {
		find(id);
		return pages.findByDocumentIdOrderByPageNumber(id).stream()
				.map(p -> new PageView(p.getPageNumber(), p.getText()))
				.toList();
	}

	@GetMapping(value = "/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE)
	public String text(@PathVariable Long id) {
		find(id);
		return cleaner.joinPages(pages.findByDocumentIdOrderByPageNumber(id).stream()
				.map(DocumentPage::getText).toList());
	}

	/**
	 * Accepts one or more PDFs. Each is stored under data/uploads, gets a Document
	 * row and a background ingest job; the response carries both so the UI can
	 * subscribe to progress right away.
	 */
	@PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.ACCEPTED)
	public List<UploadResult> upload(@RequestParam("files") List<MultipartFile> files) throws IOException {
		if (files == null || files.isEmpty()) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No files were uploaded.");
		}
		Files.createDirectories(props.uploadsDir());

		List<UploadResult> results = new ArrayList<>();
		for (MultipartFile file : files) {
			String original = safeFilename(file.getOriginalFilename());
			if (!isPdf(file, original)) {
				throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
						"\"" + original + "\" isn't a PDF. Only PDF files are supported right now.");
			}

			Document doc = new Document();
			doc.setTitle(Titles.fromFilename(original));
			doc.setFilename(original);
			doc.setSourceKind(Document.SourceKind.PDF);
			doc.setStatus(Document.Status.QUEUED);
			doc = documents.save(doc);

			Path stored = props.uploadsDir().resolve(doc.getId() + "-" + original).toAbsolutePath();
			try (InputStream in = file.getInputStream()) {
				Files.copy(in, stored, StandardCopyOption.REPLACE_EXISTING);
			}
			doc.setStoredPath(stored.toString());
			doc = documents.save(doc);

			JobService.JobView job = jobs.create(doc.getId(), "INGEST", original);
			pipeline.ingestPdf(doc.getId(), job.id());
			results.add(new UploadResult(DocumentSummary.of(doc), job));
		}
		return results;
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) throws IOException {
		Document doc = find(id);
		pages.deleteByDocumentId(id);
		artifacts.deleteByDocumentId(id);
		documents.delete(doc);
		if (doc.getStoredPath() != null) {
			Files.deleteIfExists(Path.of(doc.getStoredPath()));
		}
	}

	private Document find(Long id) {
		return documents.findById(id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Document " + id + " not found."));
	}

	private static boolean isPdf(MultipartFile file, String name) throws IOException {
		boolean byName = name.toLowerCase(Locale.ROOT).endsWith(".pdf");
		try (InputStream in = file.getInputStream()) {
			byte[] head = in.readNBytes(PDF_MAGIC.length);
			return byName || Arrays.equals(head, PDF_MAGIC);
		}
	}

	/** Keep just the base name and strip characters that are unsafe in a path. */
	private static String safeFilename(String original) {
		String base = original == null ? "upload.pdf" : original;
		int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
		if (slash >= 0) base = base.substring(slash + 1);
		base = base.replaceAll("[^A-Za-z0-9._ ()\\-]", "_").trim();
		return base.isEmpty() ? "upload.pdf" : base;
	}
}
