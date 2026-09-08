package app.study.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import app.study.jobs.JobService;

/** Poll or stream the progress of a background job. */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

	private final JobService jobs;

	public JobController(JobService jobs) {
		this.jobs = jobs;
	}

	@GetMapping("/{id}")
	public JobService.JobView get(@PathVariable String id) {
		return jobs.get(id).orElseThrow(() -> notFound(id));
	}

	/** Server-Sent Events: one {@code job} event per state change, then the stream closes. */
	@GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter events(@PathVariable String id) {
		return jobs.subscribe(id).orElseThrow(() -> notFound(id));
	}

	private static ResponseStatusException notFound(String id) {
		return new ResponseStatusException(HttpStatus.NOT_FOUND,
				"Job " + id + " not found (jobs don't survive a restart).");
	}
}
