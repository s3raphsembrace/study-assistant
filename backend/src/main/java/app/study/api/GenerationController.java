package app.study.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import app.study.jobs.GenerationPipeline;
import app.study.jobs.JobService;
import app.study.store.Artifact;
import app.study.store.ArtifactRepository;
import app.study.store.Document;
import app.study.store.DocumentRepository;

/** Generated material (notes, key terms, flashcards, quiz, spoken text) per document. */
@RestController
@RequestMapping("/api/documents/{documentId}")
public class GenerationController {

	public enum ArtifactStatus { MISSING, GENERATING, READY, ERROR }

	public record ArtifactState(Artifact.Kind kind, ArtifactStatus status, String model,
			Instant createdAt, String jobId, String error) {}

	public record ArtifactView(Artifact.Kind kind, String content, String model, Instant createdAt) {}

	public record GenerateRequest(Integer count, String difficulty, List<String> types) {}

	private final DocumentRepository documents;
	private final ArtifactRepository artifacts;
	private final GenerationPipeline pipeline;
	private final JobService jobs;

	public GenerationController(DocumentRepository documents, ArtifactRepository artifacts,
			GenerationPipeline pipeline, JobService jobs) {
		this.documents = documents;
		this.artifacts = artifacts;
		this.pipeline = pipeline;
		this.jobs = jobs;
	}

	/**
	 * One row per kind so the UI can render every tab's state at once. An
	 * existing artifact stays READY while a regeneration runs; {@code jobId} is
	 * set whenever a job is in flight so the UI can follow it either way.
	 */
	@GetMapping("/artifacts")
	public List<ArtifactState> list(@PathVariable Long documentId) {
		find(documentId);
		List<ArtifactState> out = new ArrayList<>();
		for (Artifact.Kind kind : Artifact.Kind.values()) {
			Optional<JobService.JobView> job = jobs.latestFor(documentId, kind.name());
			Optional<Artifact> art = artifacts.findByDocumentIdAndKind(documentId, kind);
			boolean running = job.isPresent() && job.get().status() == JobService.Status.RUNNING;
			if (art.isPresent()) {
				out.add(new ArtifactState(kind, ArtifactStatus.READY, art.get().getModel(),
						art.get().getCreatedAt(), running ? job.get().id() : null, null));
			} else if (running) {
				out.add(new ArtifactState(kind, ArtifactStatus.GENERATING, null, null, job.get().id(), null));
			} else if (job.isPresent() && job.get().status() == JobService.Status.ERROR) {
				out.add(new ArtifactState(kind, ArtifactStatus.ERROR, null, null, job.get().id(), job.get().error()));
			} else {
				out.add(new ArtifactState(kind, ArtifactStatus.MISSING, null, null, null, null));
			}
		}
		return out;
	}

	@GetMapping("/artifacts/{kind}")
	public ArtifactView get(@PathVariable Long documentId, @PathVariable String kind) {
		find(documentId);
		Artifact.Kind k = parseKind(kind);
		Artifact a = artifacts.findByDocumentIdAndKind(documentId, k).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No " + kind + " generated yet."));
		return new ArtifactView(a.getKind(), a.getContent(), a.getModel(), a.getCreatedAt());
	}

	/** Start (or restart) generation of one kind. Returns the job to follow. */
	@PostMapping("/generate/{kind}")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobService.JobView generate(@PathVariable Long documentId, @PathVariable String kind,
			@RequestBody(required = false) GenerateRequest body) {
		Document doc = find(documentId);
		if (doc.getStatus() != Document.Status.READY) {
			throw new ResponseStatusException(HttpStatus.CONFLICT,
					"The document's text isn't ready yet (status " + doc.getStatus() + ").");
		}
		Artifact.Kind k = parseKind(kind);
		Optional<JobService.JobView> running = jobs.latestFor(documentId, k.name())
				.filter(j -> j.status() == JobService.Status.RUNNING);
		if (running.isPresent()) return running.get();

		JobService.JobView job = jobs.create(documentId, k.name(), doc.getTitle());
		GenerationPipeline.Request req = body == null
				? GenerationPipeline.Request.DEFAULTS
				: new GenerationPipeline.Request(body.count(), body.difficulty(), body.types(), null, null);
		pipeline.start(documentId, k, req, job.id());
		return job;
	}

	private Document find(Long id) {
		return documents.findById(id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Document " + id + " not found."));
	}

	private static Artifact.Kind parseKind(String s) {
		try {
			return Artifact.Kind.valueOf(s.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
		} catch (IllegalArgumentException e) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown artifact kind: " + s);
		}
	}
}
