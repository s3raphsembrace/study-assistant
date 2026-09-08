import { DecimalPipe } from '@angular/common';
import { Component, DestroyRef, ElementRef, computed, effect, inject, input, signal, viewChildren } from '@angular/core';
import { VoiceOption, loadVoices, splitSentences } from './speech';

type PlayState = 'idle' | 'playing' | 'paused';

const RATE_KEY = 'reader.rate';
const VOICE_KEY = 'reader.voice';

/**
 * Reads text aloud with the browser's speech synthesis, one sentence at a
 * time, highlighting the sentence being spoken. Works offline with whatever
 * voices the operating system provides.
 */
@Component({
  selector: 'app-reader',
  imports: [DecimalPipe],
  templateUrl: './reader.html',
  styleUrl: './reader.scss',
})
export class Reader {
  /** Plain text to read. */
  readonly text = input.required<string>();

  private readonly destroyRef = inject(DestroyRef);
  private readonly sentenceEls = viewChildren<ElementRef<HTMLElement>>('sentence');

  protected readonly supported = typeof globalThis.speechSynthesis !== 'undefined';
  protected readonly sentences = computed(() => splitSentences(this.text()));
  protected readonly index = signal(0);
  protected readonly state = signal<PlayState>('idle');
  protected readonly rate = signal(readNumber(RATE_KEY, 1));
  protected readonly voices = signal<VoiceOption[]>([]);
  protected readonly voiceName = signal(readString(VOICE_KEY, ''));

  /** Incremented on every stop so stale utterance callbacks can be ignored. */
  private session = 0;

  constructor() {
    loadVoices().then((vs) => {
      this.voices.set(vs);
      if (!vs.some((v) => v.name === this.voiceName())) {
        const preferred = vs.find((v) => v.lang.toLowerCase().startsWith('en')) ?? vs[0];
        if (preferred) this.voiceName.set(preferred.name);
      }
    });
    // New text: start over.
    effect(() => {
      this.sentences();
      this.stop();
    });
    this.destroyRef.onDestroy(() => this.stop());
  }

  // ---- controls -----------------------------------------------------------

  protected toggle(): void {
    switch (this.state()) {
      case 'playing':
        speechSynthesis.pause();
        this.state.set('paused');
        break;
      case 'paused':
        speechSynthesis.resume();
        this.state.set('playing');
        break;
      default:
        this.playFrom(this.index());
    }
  }

  protected stop(): void {
    this.session++;
    if (this.supported) speechSynthesis.cancel();
    this.state.set('idle');
  }

  protected restart(): void {
    this.stop();
    this.index.set(0);
    this.playFrom(0);
  }

  protected skip(delta: number): void {
    const next = Math.max(0, Math.min(this.sentences().length - 1, this.index() + delta));
    const wasPlaying = this.state() !== 'idle';
    this.stop();
    this.index.set(next);
    if (wasPlaying) this.playFrom(next);
  }

  protected jumpTo(i: number): void {
    const wasPlaying = this.state() !== 'idle';
    this.stop();
    this.index.set(i);
    if (wasPlaying) this.playFrom(i);
  }

  protected setRate(value: string): void {
    const r = Number(value);
    this.rate.set(r);
    localStorage.setItem(RATE_KEY, String(r));
    if (this.state() !== 'idle') {
      // Rate applies per utterance: restart the current sentence with the new one.
      const i = this.index();
      this.stop();
      this.playFrom(i);
    }
  }

  protected setVoice(name: string): void {
    this.voiceName.set(name);
    localStorage.setItem(VOICE_KEY, name);
    if (this.state() !== 'idle') {
      const i = this.index();
      this.stop();
      this.playFrom(i);
    }
  }

  // ---- playback -----------------------------------------------------------

  private playFrom(i: number): void {
    if (!this.supported || !this.sentences().length) return;
    speechSynthesis.cancel();
    const session = ++this.session;
    this.state.set('playing');
    this.speak(i, session);
  }

  private speak(i: number, session: number): void {
    const list = this.sentences();
    if (session !== this.session) return;
    if (i >= list.length) {
      this.state.set('idle');
      this.index.set(0);
      return;
    }
    this.index.set(i);
    this.scrollIntoView(i);

    const u = new SpeechSynthesisUtterance(list[i]);
    u.rate = this.rate();
    const v = this.voices().find((x) => x.name === this.voiceName());
    if (v) {
      u.voice = v.voice;
      u.lang = v.lang;
    }
    u.onend = () => this.speak(i + 1, session);
    u.onerror = (ev) => {
      // "interrupted"/"canceled" are what cancel() produces; anything else is real.
      if (ev.error === 'interrupted' || ev.error === 'canceled') return;
      console.warn('Speech synthesis error', ev.error);
      this.speak(i + 1, session);
    };
    speechSynthesis.speak(u);
  }

  private scrollIntoView(i: number): void {
    const el = this.sentenceEls()[i]?.nativeElement;
    el?.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }
}

function readNumber(key: string, fallback: number): number {
  try {
    const v = Number(localStorage.getItem(key));
    return v > 0 ? v : fallback;
  } catch {
    return fallback;
  }
}

function readString(key: string, fallback: string): string {
  try {
    return localStorage.getItem(key) ?? fallback;
  } catch {
    return fallback;
  }
}
