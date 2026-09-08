import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

export interface Health {
  ok: boolean;
  service: string;
}

export type SourceKind = 'PDF' | 'AUDIO' | 'YOUTUBE';
export type DocumentStatus = 'QUEUED' | 'EXTRACTING' | 'READY' | 'ERROR';

export interface DocumentSummary {
  id: number;
  title: string;
  filename: string;
  sourceKind: SourceKind;
  status: DocumentStatus;
  pageCount: number | null;
  charCount: number | null;
  error: string | null;
  createdAt: string;
  updatedAt: string;
  /** Page images are available at /api/documents/{id}/pages/{n}/image. */
  hasImages: boolean;
}

export interface PageView {
  pageNumber: number;
  text: string;
}

export type JobStatus = 'RUNNING' | 'DONE' | 'ERROR';

export interface JobView {
  id: string;
  documentId: number | null;
  kind: string;
  label: string;
  stage: string;
  status: JobStatus;
  progress: number;
  message: string;
  error: string | null;
}

export interface UploadResult {
  document: DocumentSummary;
  job: JobView;
}

export type ArtifactKind = 'NOTES' | 'KEY_TERMS' | 'FLASHCARDS' | 'QUIZ' | 'SPOKEN' | 'AUDIO';
export type ArtifactStatus = 'MISSING' | 'GENERATING' | 'READY' | 'ERROR';

export interface ArtifactState {
  kind: ArtifactKind;
  status: ArtifactStatus;
  model: string | null;
  createdAt: string | null;
  jobId: string | null;
  error: string | null;
}

export interface ArtifactView {
  kind: ArtifactKind;
  content: string;
  model: string | null;
  createdAt: string;
}

export interface KeyTerm {
  term: string;
  definition: string;
}

export interface Flashcard {
  front: string;
  back: string;
  topic: string;
}

export type QuestionType = 'mcq' | 'true_false' | 'fill_blank';

export interface QuizQuestion {
  type: QuestionType;
  topic: string;
  question: string;
  options: string[];
  correctIndex: number;
  explanation: string;
}

export interface GenerateRequest {
  count?: number;
  difficulty?: string;
  types?: QuestionType[];
}

export interface Settings {
  chatModel: string;
  language: string;
  autoNotes: boolean;
  ollama: {
    baseUrl: string;
    reachable: boolean;
    models: string[];
  };
}

export type SettingsUpdate = Partial<Pick<Settings, 'chatModel' | 'language' | 'autoNotes'>>;

export interface TtsStatus {
  pythonFound: boolean;
  kokoroInstalled: boolean;
  modelPresent: boolean;
  voicesPresent: boolean;
  running: boolean;
  modelPath: string | null;
  voicesPath: string | null;
  voices: string[];
  defaultVoice: string;
  problem: string | null;
  ready: boolean;
}

export interface AudioInfo {
  voice: string;
  speed: number;
  sampleRate: number;
  bytes: number;
  seconds: number;
  model: string | null;
}

/**
 * Typed client for the local Spring backend. All paths are relative so the
 * same code works behind the Angular dev proxy (:4200 -> :8080) and when the
 * built app is served by Spring itself.
 */
@Injectable({ providedIn: 'root' })
export class ApiService {
  private readonly http = inject(HttpClient);

  health(): Observable<Health> {
    return this.http.get<Health>('/api/health');
  }

  // ---- documents ----------------------------------------------------------

  listDocuments(): Observable<DocumentSummary[]> {
    return this.http.get<DocumentSummary[]>('/api/documents');
  }

  getDocument(id: number): Observable<DocumentSummary> {
    return this.http.get<DocumentSummary>(`/api/documents/${id}`);
  }

  getPages(id: number): Observable<PageView[]> {
    return this.http.get<PageView[]>(`/api/documents/${id}/pages`);
  }

  getText(id: number): Observable<string> {
    return this.http.get(`/api/documents/${id}/text`, { responseType: 'text' });
  }

  deleteDocument(id: number): Observable<void> {
    return this.http.delete<void>(`/api/documents/${id}`);
  }

  uploadDocuments(files: File[]): Observable<UploadResult[]> {
    const form = new FormData();
    for (const f of files) form.append('files', f, f.name);
    return this.http.post<UploadResult[]>('/api/documents', form);
  }

  // ---- generated material -------------------------------------------------

  listArtifacts(documentId: number): Observable<ArtifactState[]> {
    return this.http.get<ArtifactState[]>(`/api/documents/${documentId}/artifacts`);
  }

  getArtifact(documentId: number, kind: ArtifactKind): Observable<ArtifactView> {
    return this.http.get<ArtifactView>(`/api/documents/${documentId}/artifacts/${kind}`);
  }

  generate(documentId: number, kind: ArtifactKind, body: GenerateRequest = {}): Observable<JobView> {
    return this.http.post<JobView>(`/api/documents/${documentId}/generate/${kind}`, body);
  }

  // ---- settings -----------------------------------------------------------

  getSettings(): Observable<Settings> {
    return this.http.get<Settings>('/api/settings');
  }

  updateSettings(patch: SettingsUpdate): Observable<Settings> {
    return this.http.put<Settings>('/api/settings', patch);
  }

  // ---- text-to-speech (Kokoro) --------------------------------------------

  ttsStatus(): Observable<TtsStatus> {
    return this.http.get<TtsStatus>('/api/tts/status');
  }

  /** Downloads the Kokoro model files (about 340 MB). Returns the job to follow. */
  ttsSetup(): Observable<JobView> {
    return this.http.post<JobView>('/api/tts/setup', {});
  }

  generateAudio(documentId: number, voice?: string, speed?: number): Observable<JobView> {
    return this.http.post<JobView>(`/api/documents/${documentId}/audio`, { voice, speed });
  }

  audioInfo(documentId: number): Observable<AudioInfo> {
    return this.http.get<AudioInfo>(`/api/documents/${documentId}/audio/info`);
  }

  audioUrl(documentId: number): string {
    return `/api/documents/${documentId}/audio`;
  }

  // ---- jobs ---------------------------------------------------------------

  getJob(id: string): Observable<JobView> {
    return this.http.get<JobView>(`/api/jobs/${id}`);
  }

  /**
   * Live job progress over Server-Sent Events. Emits every state change and
   * completes when the backend closes the stream (job done or failed).
   */
  jobEvents(jobId: string): Observable<JobView> {
    return new Observable<JobView>((subscriber) => {
      const source = new EventSource(`/api/jobs/${jobId}/events`);
      source.addEventListener('job', (ev) => {
        const job = JSON.parse((ev as MessageEvent).data) as JobView;
        subscriber.next(job);
        if (job.status !== 'RUNNING') {
          source.close();
          subscriber.complete();
        }
      });
      source.onerror = () => {
        // EventSource fires "error" both on real failures and when the server
        // closes the stream after the terminal event. Either way we're done.
        source.close();
        subscriber.complete();
      };
      return () => source.close();
    });
  }
}

/** Pull a human-readable message out of an HttpErrorResponse. */
export function errorMessage(err: unknown): string {
  const e = err as { error?: { error?: string }; message?: string; status?: number };
  if (e?.error?.error) return e.error.error;
  if (e?.status === 0) return 'Backend is not reachable.';
  return e?.message ?? 'Something went wrong.';
}
