package app.study.jobs;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * In-memory registry of background jobs with Server-Sent-Events fan-out so the
 * UI can show live progress. Jobs are transient (a restart forgets them); the
 * durable outcome lives on the Document row's status.
 */
@Service
public class JobService {

	private static final Logger log = LoggerFactory.getLogger(JobService.class);

	public enum Status { RUNNING, DONE, ERROR }

	/** Snapshot sent to clients. */
	public record JobView(
			String id,
			Long documentId,
			/** What the job produces: "INGEST" or an Artifact.Kind name. */
			String kind,
			String label,
			String stage,
			Status status,
			double progress,
			String message,
			String error) {}

	private static final class JobState {
		final String id;
		final Long documentId;
		final String kind;
		final String label;
		final long createdAt = System.nanoTime();
		volatile String stage = "queued";
		volatile Status status = Status.RUNNING;
		volatile double progress = 0;
		volatile String message = "Queued";
		volatile String error;
		final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

		JobState(String id, Long documentId, String kind, String label) {
			this.id = id;
			this.documentId = documentId;
			this.kind = kind;
			this.label = label;
		}

		JobView view() {
			return new JobView(id, documentId, kind, label, stage, status, progress, message, error);
		}
	}

	private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

	public JobView create(Long documentId, String kind, String label) {
		String id = UUID.randomUUID().toString();
		JobState s = new JobState(id, documentId, kind, label);
		jobs.put(id, s);
		return s.view();
	}

	/** The most recently created job for this document and kind, whatever its status. */
	public Optional<JobView> latestFor(Long documentId, String kind) {
		return jobs.values().stream()
				.filter(j -> documentId.equals(j.documentId) && kind.equals(j.kind))
				.max(java.util.Comparator.comparingLong(j -> j.createdAt))
				.map(JobState::view);
	}

	public Optional<JobView> get(String id) {
		return Optional.ofNullable(jobs.get(id)).map(JobState::view);
	}

	public void progress(String id, String stage, double progress, String message) {
		JobState s = jobs.get(id);
		if (s == null) return;
		s.stage = stage;
		s.progress = Math.max(0, Math.min(1, progress));
		s.message = message;
		broadcast(s, false);
	}

	public void done(String id, String message) {
		JobState s = jobs.get(id);
		if (s == null) return;
		s.status = Status.DONE;
		s.progress = 1;
		s.message = message;
		broadcast(s, true);
	}

	public void fail(String id, String error) {
		JobState s = jobs.get(id);
		if (s == null) return;
		s.status = Status.ERROR;
		s.error = error;
		s.message = "Failed";
		broadcast(s, true);
	}

	/** Attach an SSE stream. Immediately replays the current state; completes once the job ends. */
	public Optional<SseEmitter> subscribe(String id) {
		JobState s = jobs.get(id);
		if (s == null) return Optional.empty();
		SseEmitter emitter = new SseEmitter(0L); // no timeout: the job decides when we're done
		emitter.onCompletion(() -> s.emitters.remove(emitter));
		emitter.onTimeout(() -> s.emitters.remove(emitter));
		emitter.onError(e -> s.emitters.remove(emitter));
		s.emitters.add(emitter);
		send(emitter, s.view());
		if (s.status != Status.RUNNING) {
			emitter.complete();
		}
		return Optional.of(emitter);
	}

	private void broadcast(JobState s, boolean terminal) {
		JobView v = s.view();
		for (SseEmitter e : s.emitters) {
			send(e, v);
			if (terminal) e.complete();
		}
	}

	private static void send(SseEmitter e, JobView v) {
		try {
			e.send(SseEmitter.event().name("job").data(v));
		} catch (IOException | IllegalStateException ex) {
			log.debug("SSE client went away: {}", ex.toString());
			e.completeWithError(ex);
		}
	}
}
