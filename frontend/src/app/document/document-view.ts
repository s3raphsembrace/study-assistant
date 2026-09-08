import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, DestroyRef, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import {
  ApiService,
  ArtifactKind,
  ArtifactState,
  AudioInfo,
  DocumentSummary,
  Flashcard,
  JobView,
  KeyTerm,
  PageView,
  QuizQuestion,
  TtsStatus,
  errorMessage,
} from '../core/api/api.service';
import { Reader } from '../reader/reader';
import { markdownToSpeech } from '../reader/speech';
import { Markdown } from '../shared/markdown';
import { Flashcards } from './flashcards';
import { Quiz } from './quiz';

type Tab = 'notes' | 'terms' | 'flashcards' | 'quiz' | 'listen' | 'pages';

const TAB_KIND: Record<Exclude<Tab, 'pages' | 'listen'>, ArtifactKind> = {
  notes: 'NOTES',
  terms: 'KEY_TERMS',
  flashcards: 'FLASHCARDS',
  quiz: 'QUIZ',
};

/** One document: extracted text plus everything generated from it. */
@Component({
  selector: 'app-document-view',
  imports: [RouterLink, DecimalPipe, DatePipe, Markdown, Flashcards, Quiz, Reader],
  templateUrl: './document-view.html',
  styleUrl: './document-view.scss',
})
export class DocumentView {
  private readonly api = inject(ApiService);
  private readonly route = inject(ActivatedRoute);
  private readonly destroyRef = inject(DestroyRef);

  protected readonly doc = signal<DocumentSummary | null>(null);
  protected readonly pages = signal<PageView[]>([]);
  protected readonly tab = signal<Tab>('notes');
  protected readonly error = signal<string | null>(null);

  /** Per-kind state from the backend, refreshed whenever a job finishes. */
  protected readonly artifacts = signal<Record<string, ArtifactState>>({});
  /** Live progress for kinds currently generating. */
  protected readonly jobs = signal<Record<string, JobView>>({});

  protected readonly notes = signal<string | null>(null);
  protected readonly terms = signal<KeyTerm[] | null>(null);
  protected readonly cards = signal<Flashcard[] | null>(null);
  protected readonly questions = signal<QuizQuestion[] | null>(null);
  protected readonly spoken = signal<string | null>(null);

  /** What the reader speaks: the generated spoken version when ready, else the notes flattened to prose. */
  protected readonly listenText = computed<string | null>(() => {
    const sp = this.spoken();
    if (sp) return sp;
    const md = this.notes();
    return md ? markdownToSpeech(md) : null;
  });

  protected readonly tabs: { id: Tab; label: string }[] = [
    { id: 'notes', label: 'Notes' },
    { id: 'terms', label: 'Key terms' },
    { id: 'flashcards', label: 'Flashcards' },
    { id: 'quiz', label: 'Quiz' },
    { id: 'listen', label: 'Listen' },
    { id: 'pages', label: 'Source text' },
  ];

  protected readonly currentKind = computed<ArtifactKind | null>(() => {
    const t = this.tab();
    return t === 'pages' || t === 'listen' ? null : TAB_KIND[t];
  });

  protected readonly spokenState = computed<ArtifactState | null>(() => this.artifacts()['SPOKEN'] ?? null);
  protected readonly spokenJob = computed<JobView | null>(() => this.jobs()['SPOKEN'] ?? null);

  /** Kokoro (natural voice) state. */
  protected readonly listenMode = signal<'browser' | 'kokoro'>('browser');
  protected readonly tts = signal<TtsStatus | null>(null);
  protected readonly ttsSetupJob = signal<JobView | null>(null);
  protected readonly audioState = computed<ArtifactState | null>(() => this.artifacts()['AUDIO'] ?? null);
  protected readonly audioJob = computed<JobView | null>(() => this.jobs()['AUDIO'] ?? null);
  protected readonly audioInfo = signal<AudioInfo | null>(null);
  protected readonly audioVoice = signal('');
  /** Cache-buster so the <audio> element reloads after a regeneration. */
  protected readonly audioVersion = signal(0);

  protected audioSrc(): string {
    return `${this.api.audioUrl(this.docId)}?v=${this.audioVersion()}`;
  }

  protected setListenMode(mode: 'browser' | 'kokoro'): void {
    this.listenMode.set(mode);
    if (mode === 'kokoro') this.refreshTts();
  }

  protected refreshTts(): void {
    this.api.ttsStatus().subscribe({
      next: (s) => {
        this.tts.set(s);
        if (!this.audioVoice()) this.audioVoice.set(s.defaultVoice);
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
    if (this.audioState()?.status === 'READY' && !this.audioInfo()) {
      this.api.audioInfo(this.docId).subscribe({ next: (i) => this.audioInfo.set(i) });
    }
  }

  protected setupTts(): void {
    this.api.ttsSetup().subscribe({
      next: (job) => {
        this.ttsSetupJob.set(job);
        this.api.jobEvents(job.id).subscribe({
          next: (j) => this.ttsSetupJob.set(j),
          complete: () => {
            const j = this.ttsSetupJob();
            if (j?.status !== 'ERROR') this.ttsSetupJob.set(null);
            this.refreshTts();
          },
        });
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected generateAudio(): void {
    this.api.generateAudio(this.docId, this.audioVoice() || undefined).subscribe({
      next: (job) => {
        this.artifacts.update((a) => ({
          ...a,
          AUDIO: { kind: 'AUDIO', status: 'GENERATING', model: null, createdAt: null, jobId: job.id, error: null },
        }));
        this.follow('AUDIO', job.id);
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  protected formatDuration(seconds: number): string {
    const m = Math.floor(seconds / 60);
    const s = Math.round(seconds % 60);
    return `${m}:${s.toString().padStart(2, '0')}`;
  }

  protected readonly currentState = computed<ArtifactState | null>(() => {
    const k = this.currentKind();
    return k ? (this.artifacts()[k] ?? null) : null;
  });

  protected readonly currentJob = computed<JobView | null>(() => {
    const k = this.currentKind();
    return k ? (this.jobs()[k] ?? null) : null;
  });

  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private jobSubs = new Map<string, Subscription>();
  private docId = 0;

  constructor() {
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe((params) => {
      this.docId = Number(params.get('id'));
      this.reset();
      this.load();
    });
    this.destroyRef.onDestroy(() => {
      this.stopPolling();
      this.jobSubs.forEach((s) => s.unsubscribe());
    });
  }

  protected setTab(tab: Tab): void {
    this.tab.set(tab);
    this.ensureContent(tab);
  }

  protected generate(kind: ArtifactKind): void {
    this.api.generate(this.docId, kind).subscribe({
      next: (job) => {
        this.artifacts.update((a) => ({
          ...a,
          [kind]: { kind, status: 'GENERATING', model: null, createdAt: null, jobId: job.id, error: null },
        }));
        this.follow(kind, job.id);
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  /** Artifact kind behind a tab, or null for tabs without one (source text, listen). */
  protected tabKind(tab: Tab): ArtifactKind | null {
    return tab === 'pages' || tab === 'listen' ? null : TAB_KIND[tab];
  }

  protected kindLabel(kind: ArtifactKind): string {
    const labels: Record<ArtifactKind, string> = {
      NOTES: 'notes',
      KEY_TERMS: 'key terms',
      FLASHCARDS: 'flashcards',
      QUIZ: 'a quiz',
      SPOKEN: 'spoken version',
      AUDIO: 'audio',
    };
    return labels[kind];
  }

  // ---- loading ------------------------------------------------------------

  private reset(): void {
    this.stopPolling();
    this.jobSubs.forEach((s) => s.unsubscribe());
    this.jobSubs.clear();
    this.doc.set(null);
    this.pages.set([]);
    this.error.set(null);
    this.artifacts.set({});
    this.jobs.set({});
    this.notes.set(null);
    this.terms.set(null);
    this.cards.set(null);
    this.questions.set(null);
    this.spoken.set(null);
    this.audioInfo.set(null);
    this.ttsSetupJob.set(null);
  }

  private load(): void {
    const id = this.docId;
    this.api.getDocument(id).subscribe({
      next: (d) => {
        if (id !== this.docId) return;
        this.doc.set(d);
        if (d.status === 'READY') {
          this.refreshArtifacts();
        } else if (d.status === 'QUEUED' || d.status === 'EXTRACTING') {
          // Still extracting (page refreshed mid-job): poll until it settles.
          this.pollTimer = setTimeout(() => this.load(), 1500);
        } else {
          this.tab.set('pages');
        }
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  private refreshArtifacts(): void {
    const id = this.docId;
    this.api.listArtifacts(id).subscribe({
      next: (list) => {
        if (id !== this.docId) return;
        const map: Record<string, ArtifactState> = {};
        for (const a of list) {
          map[a.kind] = a;
          // jobId is set whenever a job is in flight, including a regeneration of a READY artifact.
          if (a.jobId && a.status !== 'ERROR') this.follow(a.kind, a.jobId);
        }
        this.artifacts.set(map);
        this.ensureContent(this.tab());
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  /** Fetch the content behind a tab once its artifact is READY. */
  private ensureContent(tab: Tab): void {
    const id = this.docId;
    if (tab === 'pages') {
      if (!this.pages().length) this.api.getPages(id).subscribe({ next: (p) => id === this.docId && this.pages.set(p) });
      return;
    }
    if (tab === 'listen') {
      // Needs the notes (fallback) and the spoken version (preferred) if either exists.
      if (!this.notes() && this.artifacts()['NOTES']?.status === 'READY') this.fetch(id, 'NOTES');
      if (!this.spoken() && this.artifacts()['SPOKEN']?.status === 'READY') this.fetch(id, 'SPOKEN');
      if (this.listenMode() === 'kokoro') this.refreshTts();
      return;
    }
    const kind = TAB_KIND[tab];
    if (this.artifacts()[kind]?.status !== 'READY') return;
    const cached =
      (kind === 'NOTES' && this.notes()) ||
      (kind === 'KEY_TERMS' && this.terms()) ||
      (kind === 'FLASHCARDS' && this.cards()) ||
      (kind === 'QUIZ' && this.questions());
    if (cached) return;
    this.fetch(id, kind);
  }

  private fetch(id: number, kind: ArtifactKind): void {
    this.api.getArtifact(id, kind).subscribe({
      next: (a) => {
        if (id !== this.docId) return;
        switch (kind) {
          case 'NOTES':
            this.notes.set(a.content);
            break;
          case 'KEY_TERMS':
            this.terms.set(parseJson<{ terms: KeyTerm[] }>(a.content)?.terms ?? []);
            break;
          case 'FLASHCARDS':
            this.cards.set(parseJson<{ cards: Flashcard[] }>(a.content)?.cards ?? []);
            break;
          case 'QUIZ':
            this.questions.set(parseJson<{ questions: QuizQuestion[] }>(a.content)?.questions ?? []);
            break;
          case 'SPOKEN':
            this.spoken.set(a.content);
            break;
        }
      },
      error: (e) => this.error.set(errorMessage(e)),
    });
  }

  private follow(kind: ArtifactKind, jobId: string): void {
    if (this.jobSubs.has(kind)) return;
    const sub = this.api.jobEvents(jobId).subscribe({
      next: (j) => this.jobs.update((m) => ({ ...m, [kind]: j })),
      complete: () => {
        this.jobSubs.delete(kind);
        this.jobs.update((m) => {
          const { [kind]: _, ...rest } = m;
          return rest;
        });
        // Drop the cached content so the regenerated version is fetched.
        if (kind === 'NOTES') this.notes.set(null);
        if (kind === 'KEY_TERMS') this.terms.set(null);
        if (kind === 'FLASHCARDS') this.cards.set(null);
        if (kind === 'QUIZ') this.questions.set(null);
        if (kind === 'SPOKEN') this.spoken.set(null);
        if (kind === 'AUDIO') {
          this.audioInfo.set(null);
          this.audioVersion.update((v) => v + 1);
          // The audio job also produces the spoken version when it was missing.
          this.spoken.set(null);
          if (this.tab() === 'listen') setTimeout(() => this.refreshTts(), 300);
        }
        this.refreshArtifacts();
        if (kind === 'NOTES') this.api.getDocument(this.docId).subscribe({ next: (d) => this.doc.set(d) });
      },
    });
    this.jobSubs.set(kind, sub);
  }

  private stopPolling(): void {
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.pollTimer = null;
  }
}

function parseJson<T>(s: string): T | null {
  try {
    return JSON.parse(s) as T;
  } catch {
    return null;
  }
}
