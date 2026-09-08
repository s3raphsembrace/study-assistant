package app.study.api;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import app.study.jobs.GenerationPipeline;
import app.study.jobs.JobService;
import app.study.store.Artifact;
import app.study.store.ArtifactRepository;
import app.study.store.Document;
import app.study.store.DocumentRepository;
import app.study.tts.TtsService;

/** Kokoro text-to-speech: setup status, model download, and per-document audio. */
@RestController
@RequestMapping("/api")
public class TtsController {

	public record AudioRequest(String voice, Double speed) {}

	public record AudioInfo(String voice, double speed, int sampleRate, long bytes, double seconds, String model) {}

	private final TtsService tts;
	private final JobService jobs;
	private final GenerationPipeline pipeline;
	private final DocumentRepository documents;
	private final ArtifactRepository artifacts;
	private final ObjectMapper json;

	public TtsController(TtsService tts, JobService jobs, GenerationPipeline pipeline,
			DocumentRepository documents, ArtifactRepository artifacts, ObjectMapper json) {
		this.tts = tts;
		this.jobs = jobs;
		this.pipeline = pipeline;
		this.documents = documents;
		this.artifacts = artifacts;
		this.json = json;
	}

	@GetMapping("/tts/status")
	public TtsService.Status status() {
		return tts.status();
	}

	/** Downloads Kokoro's model files (about 340 MB). Runs as a job; only triggered by the user. */
	@PostMapping("/tts/setup")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobService.JobView setup() {
		Optional<JobService.JobView> running = jobs.latestFor(0L, "TTS_SETUP")
				.filter(j -> j.status() == JobService.Status.RUNNING);
		if (running.isPresent()) return running.get();
		JobService.JobView job = jobs.create(0L, "TTS_SETUP", "Kokoro voice model");
		Thread.ofVirtual().name("tts-setup").start(() -> {
			try {
				jobs.progress(job.id(), "download", 0, "Downloading Kokoro voice model…");
				tts.downloadModels(p -> jobs.progress(job.id(), "download", p,
						"Downloading Kokoro voice model… " + Math.round(p * 100) + "%"));
				jobs.done(job.id(), "Kokoro is ready");
			} catch (Exception e) {
				jobs.fail(job.id(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
			}
		});
		return job;
	}

	/** Generate (or regenerate) the audio for a document's notes. */
	@PostMapping("/documents/{documentId}/audio")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobService.JobView generateAudio(@PathVariable Long documentId, @RequestBody(required = false) AudioRequest body) {
		Document doc = find(documentId);
		if (doc.getStatus() != Document.Status.READY) {
			throw new ResponseStatusException(HttpStatus.CONFLICT, "The document's text isn't ready yet.");
		}
		TtsService.Status st = tts.status();
		if (!st.ready()) throw new ResponseStatusException(HttpStatus.CONFLICT, st.problem());

		Optional<JobService.JobView> running = jobs.latestFor(documentId, Artifact.Kind.AUDIO.name())
				.filter(j -> j.status() == JobService.Status.RUNNING);
		if (running.isPresent()) return running.get();

		JobService.JobView job = jobs.create(documentId, Artifact.Kind.AUDIO.name(), doc.getTitle());
		GenerationPipeline.Request req = new GenerationPipeline.Request(null, null, null,
				body != null ? body.voice() : null, body != null ? body.speed() : null);
		pipeline.start(documentId, Artifact.Kind.AUDIO, req, job.id());
		return job;
	}

	@GetMapping("/documents/{documentId}/audio/info")
	public AudioInfo audioInfo(@PathVariable Long documentId) {
		find(documentId);
		Artifact a = artifacts.findByDocumentIdAndKind(documentId, Artifact.Kind.AUDIO).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No audio generated yet."));
		JsonNode n = json.readTree(a.getContent());
		return new AudioInfo(n.path("voice").asText(), n.path("speed").asDouble(1), n.path("sampleRate").asInt(),
				n.path("bytes").asLong(), n.path("seconds").asDouble(), a.getModel());
	}

	/** The WAV itself. Spring honours Range requests for Resource bodies, so seeking works. */
	@GetMapping(value = "/documents/{documentId}/audio", produces = "audio/wav")
	public ResponseEntity<Resource> audio(@PathVariable Long documentId) throws IOException {
		find(documentId);
		Artifact a = artifacts.findByDocumentIdAndKind(documentId, Artifact.Kind.AUDIO).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "No audio generated yet."));
		Path file = Path.of(json.readTree(a.getContent()).path("path").asText());
		if (!Files.isRegularFile(file)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The audio file is missing; generate it again.");
		}
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType("audio/wav"))
				.header("Content-Disposition", "inline; filename=\"notes-" + documentId + ".wav\"")
				.header("Accept-Ranges", "bytes")
				.body(new FileSystemResource(file));
	}

	private Document find(Long id) {
		return documents.findById(id).orElseThrow(() ->
				new ResponseStatusException(HttpStatus.NOT_FOUND, "Document " + id + " not found."));
	}
}
