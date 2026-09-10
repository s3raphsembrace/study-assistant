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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;

import app.study.config.AppProperties;
import app.study.ingest.PageRenderer;
import app.study.ingest.TextCleaner;
import app.study.jobs.IngestPipeline;
import app.study.jobs.JobService;
import app.study.jobs.Titles;
import app.study.store.ArtifactRepository;
import app.study.store.Document;
import app.study.store.DocumentPage;
import app.study.media.YoutubeExtractor;
import app.study.store.DocumentPageRepository;
import app.study.store.DocumentRepository;

/** Upload, list, read and delete study documents. */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

	private static final byte[] PDF_MAGIC = "%PDF".getBytes(StandardCharsets.US_ASCII);

	/** Everything ffmpeg will happily decode for us; the audio track is all we keep. */
	private static final List<String> AUDIO_EXTENSIONS = List.of(
			".mp3", ".m4a", ".wav", ".aac", ".ogg", ".oga", ".opus", ".flac", ".wma", ".aiff", ".aif");
	private static final List<String> VIDEO_EXTENSIONS = List.of(
			".mp4", ".m4v", ".mov", ".mkv", ".webm", ".avi", ".wmv", ".flv", ".mpg", ".mpeg", ".ts");

	public record DocumentSummary(
			Long id, String title, String filename, Document.SourceKind sourceKind,
			Document.Status status, Integer pageCount, Integer charCount, String error,
			Instant createdAt, Instant updatedAt, boolean hasImages,
			String sourceUrl, Double durationSeconds) {

		static DocumentSummary of(Document d, boolean hasImages) {
			return new DocumentSummary(d.getId(), d.getTitle(), d.getFilename(), d.getSourceKind(),
					d.getStatus(), d.getPageCount(), d.getCharCount(), d.getError(),
					d.getCreatedAt(), d.getUpdatedAt(), hasImages,
					d.getSourceUrl(), d.getDurationSeconds());
		}
	}

	/** Body of {@code POST /api/documents/url}. */
	public record UrlRequest(String url) {}

	public record PageView(int pageNumber, String text) {}

	public record UploadResult(DocumentSummary document, JobService.JobView job) {}

	private final DocumentRepository documents;
	private final DocumentPageRepository pages;
	private final ArtifactRepository artifacts;
	private final IngestPipeline pipeline;
	private final JobService jobs;
	private final TextCleaner cleaner;
	private final AppProperties props;
	private final PageRenderer renderer;

	public DocumentController(DocumentRepository documents, DocumentPageRepository pages,
			ArtifactRepository artifacts, IngestPipeline pipeline, JobService jobs, TextCleaner cleaner,
			AppProperties props, PageRenderer renderer) {
		this.documents = documents;
		this.pages = pages;
		this.artifacts = artifacts;
		this.pipeline = pipeline;
		this.jobs = jobs;
		this.cleaner = cleaner;
		this.props = props;
		this.renderer = renderer;
	}

	private DocumentSummary summary(Document d) {
		// PDFs always have images: they're rendered on first request if missing.
		return DocumentSummary.of(d, d.getSourceKind() == Document.SourceKind.PDF
				&& d.getStatus() == Document.Status.READY && d.getStoredPath() != null);
	}

	@GetMapping
	public List<DocumentSummary> list() {
		return documents.findAllByOrderByCreatedAtDesc().stream().map(this::summary).toList();
	}

	@GetMapping("/{id}")
	public DocumentSummary get(@PathVariable Long id) {
		return summary(find(id));
	}

	@GetMapping("/{id}/pages")
	public List<PageView> pages(@PathVariable Long id) {
		find(id);
		return pages.findByDocumentIdOrderByPageNumber(id).stream()
				.map(p -> new PageView(p.getPageNumber(), p.getText()))
				.toList();
	}

	/** Rendered page image (JPEG). Rendered on demand for documents that predate page images. */
	@GetMapping(value = "/{id}/pages/{page}/image", produces = "image/jpeg")
	public ResponseEntity<Resource> pageImage(@PathVariable Long id, @PathVariable int page) throws IOException {
		Document doc = find(id);
		if (page < 1 || (doc.getPageCount() != null && page > doc.getPageCount())) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No page " + page + ".");
		}
		Path img = renderer.imagePath(id, page);
		if (!Files.isRegularFile(img)) {
			if (doc.getStoredPath() == null || !Files.isRegularFile(Path.of(doc.getStoredPath()))) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The original PDF is no longer available.");
			}
			renderer.renderAll(id, Path.of(doc.getStoredPath()), null);
			if (!Files.isRegularFile(img)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No page " + page + ".");
		}
		return ResponseEntity.ok()
				.contentType(MediaType.IMAGE_JPEG)
				.cacheControl(CacheControl.maxAge(30, TimeUnit.DAYS))
				.body(new FileSystemResource(img));
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
			Document.SourceKind kind = kindOf(file, original);

			Document doc = new Document();
			doc.setTitle(Titles.fromFilename(original));
			doc.setFilename(original);
			doc.setSourceKind(kind);
			doc.setStatus(Document.Status.QUEUED);
			doc = documents.save(doc);

			Path stored = props.uploadsDir().resolve(doc.getId() + "-" + original).toAbsolutePath();
			try (InputStream in = file.getInputStream()) {
				Files.copy(in, stored, StandardCopyOption.REPLACE_EXISTING);
			}
			doc.setStoredPath(stored.toString());
			doc = documents.save(doc);

			JobService.JobView job = jobs.create(doc.getId(), "INGEST", original);
			if (kind == Document.SourceKind.PDF) pipeline.ingestPdf(doc.getId(), job.id());
			else pipeline.ingestMedia(doc.getId(), job.id());
			results.add(new UploadResult(summary(doc), job));
		}
		return results;
	}

	/** Add a YouTube lecture by link. Captions are used when the video has them. */
	@PostMapping(value = "/url", consumes = MediaType.APPLICATION_JSON_VALUE)
	@ResponseStatus(HttpStatus.ACCEPTED)
	public UploadResult addUrl(@RequestBody UrlRequest body) {
		String url = body == null || body.url() == null ? "" : body.url().strip();
		if (!YoutubeExtractor.isYoutube(url)) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
					"That doesn't look like a YouTube link. Other sites aren't supported yet.");
		}
		Document doc = new Document();
		doc.setTitle("YouTube video");
		doc.setFilename(url);
		doc.setSourceUrl(url);
		doc.setSourceKind(Document.SourceKind.YOUTUBE);
		doc.setStatus(Document.Status.QUEUED);
		doc = documents.save(doc);

		JobService.JobView job = jobs.create(doc.getId(), "INGEST", url);
		pipeline.ingestYoutube(doc.getId(), job.id());
		return new UploadResult(summary(doc), job);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) throws IOException {
		Document doc = find(id);
		pages.deleteByDocumentId(id);
		artifacts.deleteByDocumentId(id);
		documents.delete(doc);
		renderer.delete(id);
		if (doc.getStoredPath() != null) {
			Files.deleteIfExists(Path.of(doc.getStoredPath()));
		}
	}

	private Document find(Long id) {
		return documents.findById(id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Document " + id + " not found."));
	}

	/** PDF, or an audio/video file for transcription. Anything else is rejected. */
	private static Document.SourceKind kindOf(MultipartFile file, String name) throws IOException {
		String lower = name.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".pdf")) return Document.SourceKind.PDF;
		if (AUDIO_EXTENSIONS.stream().anyMatch(lower::endsWith)
				|| VIDEO_EXTENSIONS.stream().anyMatch(lower::endsWith)) {
			return Document.SourceKind.AUDIO;
		}
		try (InputStream in = file.getInputStream()) {
			if (Arrays.equals(in.readNBytes(PDF_MAGIC.length), PDF_MAGIC)) return Document.SourceKind.PDF;
		}
		throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
				"\"" + name + "\" isn't a PDF, audio or video file.");
	}

	/** Keep just the base name and strip characters that are unsafe in a path. */
	private static String safeFilename(String original) {
		String base = original == null ? "upload.pdf" : original;
		int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
		if (slash >= 0) base = base.substring(slash + 1);
		base = base.replaceAll("[^A-Za-z0-9._ ()\\-]", "_").trim();
		return base.isEmpty() ? "upload" : base;
	}
}
