package app.study.api;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import app.study.jobs.JobService;
import app.study.media.MediaTools;

/** Setup state and one-click install for the audio, video and YouTube tools. */
@RestController
@RequestMapping("/api/media")
public class MediaController {

	private static final String SETUP_KIND = "MEDIA_SETUP";

	private final MediaTools tools;
	private final JobService jobs;

	public MediaController(MediaTools tools, JobService jobs) {
		this.tools = tools;
		this.jobs = jobs;
	}

	@GetMapping("/status")
	public MediaTools.Status status() {
		return tools.status();
	}

	/**
	 * Downloads ffmpeg, whisper.cpp with its speech model, and yt-dlp into
	 * tools/. Runs as a job; only ever started by an explicit user action.
	 */
	@PostMapping("/setup")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public JobService.JobView setup() {
		Optional<JobService.JobView> running = jobs.latestFor(0L, SETUP_KIND)
				.filter(j -> j.status() == JobService.Status.RUNNING);
		if (running.isPresent()) return running.get();

		JobService.JobView job = jobs.create(0L, SETUP_KIND, "Media tools");
		Thread.ofVirtual().name("media-setup").start(() -> {
			try {
				jobs.progress(job.id(), "download", 0, "Starting download…");
				tools.install(
						p -> jobs.progress(job.id(), "download", p, currentMessage(job.id())),
						m -> jobs.progress(job.id(), "download", currentProgress(job.id()), m));
				jobs.done(job.id(), "Media tools ready");
			} catch (Exception e) {
				jobs.fail(job.id(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
			}
		});
		return job;
	}

	/* The installer reports progress and step labels separately; keep whichever
	   half isn't being updated so the job never flickers back to a stale value. */

	private String currentMessage(String jobId) {
		return jobs.get(jobId).map(JobService.JobView::message).orElse("Downloading…");
	}

	private double currentProgress(String jobId) {
		return jobs.get(jobId).map(JobService.JobView::progress).orElse(0.0);
	}
}
