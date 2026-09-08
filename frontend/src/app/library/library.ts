import { DatePipe } from '@angular/common';
import { Component, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { ApiService, DocumentSummary, JobView, errorMessage } from '../core/api/api.service';
import { DropZone } from '../upload/drop-zone';

/** Home screen: the drop zone, live upload progress, and every processed document. */
@Component({
  selector: 'app-library',
  imports: [DropZone, RouterLink, DatePipe],
  templateUrl: './library.html',
  styleUrl: './library.scss',
})
export class Library {
  private readonly api = inject(ApiService);

  protected readonly documents = signal<DocumentSummary[]>([]);
  protected readonly jobs = signal<JobView[]>([]);
  protected readonly uploading = signal(false);
  protected readonly error = signal<string | null>(null);

  constructor() {
    this.reload();
  }

  protected reload(): void {
    this.api.listDocuments().subscribe({
      next: (docs) => this.documents.set(docs),
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected onFiles(files: File[]): void {
    this.error.set(null);
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
    this.error.set(`Skipped ${files.map((f) => f.name).join(', ')}: only PDF files are supported right now.`);
  }

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
