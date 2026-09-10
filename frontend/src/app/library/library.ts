import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import {
  ApiService,
  DocumentSummary,
  JobView,
  MediaStatus,
  errorMessage,
} from '../core/api/api.service';
import { DropZone } from '../upload/drop-zone';

/** Extensions the backend can ingest: PDFs directly, media through whisper.cpp. */
const PDF_EXT = ['.pdf'];
const AUDIO_EXT = ['.mp3', '.m4a', '.wav', '.aac', '.ogg', '.oga', '.opus', '.flac', '.wma', '.aiff', '.aif'];
const VIDEO_EXT = ['.mp4', '.m4v', '.mov', '.mkv', '.webm', '.avi', '.wmv', '.flv', '.mpg', '.mpeg', '.ts'];

/** Home screen: the drop zone, a YouTube box, live progress, and the library. */
@Component({
  selector: 'app-library',
  imports: [DropZone, RouterLink, DatePipe],
  templateUrl: './library.html',
  styleUrl: './library.scss',
})
export class Library {
  private readonly api = inject(ApiService);

  protected readonly accept = [...PDF_EXT, ...AUDIO_EXT, ...VIDEO_EXT];
  protected readonly acceptHint = 'PDF, audio (mp3, m4a, wav…) or video (mp4, mov, mkv…)';

  protected readonly documents = signal<DocumentSummary[]>([]);
  protected readonly jobs = signal<JobView[]>([]);
  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly url = signal('');
  protected readonly media = signal<MediaStatus | null>(null);
  protected readonly mediaSetupJob = signal<JobView | null>(null);

  constructor() {
    this.reload();
    this.refreshMedia();
  }

  protected reload(): void {
    this.api.listDocuments().subscribe({
      next: (docs) => this.documents.set(docs),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected refreshMedia(): void {
    this.api.mediaStatus().subscribe({
      next: (m) => this.media.set(m),
      error: () => this.media.set(null),
    });
  }

  // ---- adding sources -----------------------------------------------------

  protected onFiles(files: File[]): void {
    this.error.set(null);
    if (this.needsMediaTools(files)) {
      this.error.set(
        'Audio and video need the transcription tools. Install them below, then try again.',
      );
      return;
    }
    this.uploading.set(true);
    this.api.uploadDocuments(files).subscribe({
      next: (results) => {
        this.uploading.set(false);
        for (const r of results) this.track(r.job);
        this.reload();
      },
      error: (e) => {
        this.uploading.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  protected onRejected(files: File[]): void {
    this.error.set(
      `Skipped ${files.map((f) => f.name).join(', ')}: only PDF, audio and video files are supported.`,
    );
  }

  protected addUrl(): void {
    const link = this.url().trim();
    if (!link) return;
    this.error.set(null);
    this.uploading.set(true);
    this.api.addUrl(link).subscribe({
      next: (result) => {
        this.uploading.set(false);
        this.url.set('');
        this.track(result.job);
        this.reload();
      },
      error: (e) => {
        this.uploading.set(false);
        this.error.set(errorMessage(e));
      },
    });
  }

  protected setupMedia(): void {
    this.api.mediaSetup().subscribe({
      next: (job) => {
        this.mediaSetupJob.set(job);
        this.api.jobEvents(job.id).subscribe({
          next: (j) => this.mediaSetupJob.set(j),
          complete: () => {
            const j = this.mediaSetupJob();
            if (j?.status !== 'ERROR') this.mediaSetupJob.set(null);
            this.refreshMedia();
          },
        });
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  /** True when the selection contains media and the tools aren't installed. */
  private needsMediaTools(files: File[]): boolean {
    const m = this.media();
    if (!m || m.transcriptionReady) return false;
    return files.some((f) => !f.name.toLowerCase().endsWith('.pdf'));
  }

  // ---- library ------------------------------------------------------------

  protected remove(doc: DocumentSummary, ev: Event): void {
    ev.preventDefault();
    ev.stopPropagation();
    if (!confirm(`Delete "${doc.title}"? This removes the extracted text and the stored file.`)) return;
    this.api.deleteDocument(doc.id).subscribe({
      next: () => this.reload(),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected activeJobs(): JobView[] {
    return this.jobs().filter((j) => j.status === 'RUNNING' || j.status === 'ERROR');
  }

  protected dismissJob(id: string): void {
    this.jobs.update((list) => list.filter((j) => j.id !== id));
  }

  protected kindLabel(doc: DocumentSummary): string {
    switch (doc.sourceKind) {
      case 'YOUTUBE':
        return 'YouTube';
      case 'AUDIO':
        return 'Recording';
      default:
        return 'PDF';
    }
  }

  protected duration(seconds: number): string {
    const total = Math.round(seconds);
    const h = Math.floor(total / 3600);
    const m = Math.floor((total % 3600) / 60);
    const s = total % 60;
    return h > 0
      ? `${h}:${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
      : `${m}:${String(s).padStart(2, '0')}`;
  }

  private track(job: JobView): void {
    this.jobs.update((list) => [job, ...list.filter((j) => j.id !== job.id)]);
    this.api.jobEvents(job.id).subscribe({
      next: (j) => this.jobs.update((list) => list.map((x) => (x.id === j.id ? j : x))),
      complete: () => {
        this.reload();
        // Finished jobs fade out on their own; failed ones stay until dismissed.
        setTimeout(() => {
          this.jobs.update((list) => list.filter((j) => j.id !== job.id || j.status === 'ERROR'));
        }, 1500);
      },
    });
  }
}
