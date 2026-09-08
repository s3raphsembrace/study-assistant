import { Component, computed, input, signal } from '@angular/core';
import { Flashcard } from '../core/api/api.service';

/** Flip-to-reveal cards, filterable by topic. */
@Component({
  selector: 'app-flashcards',
  template: `
    <div class="toolbar">
      <label>
        Topic
        <select (change)="topic.set($any($event.target).value)">
          <option value="">All ({{ cards().length }})</option>
          @for (t of topics(); track t) {
            <option [value]="t">{{ t }}</option>
          }
        </select>
      </label>
      <button type="button" class="ghost" (click)="toggleAll()">
        {{ allFlipped() ? 'Hide all answers' : 'Show all answers' }}
      </button>
    </div>

    <div class="grid">
      @for (c of visible(); track c.index) {
        <button
          type="button"
          class="card"
          [class.flipped]="flipped().has(c.index)"
          (click)="flip(c.index)"
          [attr.aria-pressed]="flipped().has(c.index)"
        >
          <span class="topic">{{ c.card.topic }}</span>
          <span class="face front">{{ c.card.front }}</span>
          @if (flipped().has(c.index)) {
            <span class="face back">{{ c.card.back }}</span>
          } @else {
            <span class="hint">Click to reveal</span>
          }
        </button>
      }
    </div>
  `,
  styles: `
    .toolbar { display: flex; gap: 1rem; align-items: center; margin-bottom: 1rem; font-size: 0.9rem; }
    label { display: flex; gap: 0.5rem; align-items: center; color: var(--ink-dim); }
    select { font: inherit; padding: 0.25rem 0.5rem; border-radius: 6px; border: 1px solid var(--edge); }
    .ghost { font: inherit; border: 1px solid var(--edge); background: none; padding: 0.3rem 0.7rem; border-radius: 6px; cursor: pointer; color: var(--ink-dim); }
    .ghost:hover { border-color: var(--accent); color: var(--ink); }
    .grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 0.75rem; }
    .card {
      display: flex; flex-direction: column; gap: 0.5rem; text-align: left; font: inherit;
      padding: 1rem; min-height: 140px; border: 1px solid var(--edge); border-radius: 12px;
      background: var(--surface); cursor: pointer; color: inherit; transition: border-color 0.15s;
    }
    .card:hover { border-color: var(--accent); }
    .card.flipped { background: var(--accent-soft, #eef1fc); border-color: var(--accent); }
    .topic { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--ink-dim); }
    .front { font-weight: 600; }
    .back { border-top: 1px dashed var(--edge); padding-top: 0.5rem; white-space: pre-wrap; }
    .hint { margin-top: auto; font-size: 0.8rem; color: var(--ink-dim); }
  `,
})
export class Flashcards {
  readonly cards = input.required<Flashcard[]>();

  protected readonly topic = signal('');
  protected readonly flipped = signal(new Set<number>());

  protected readonly topics = computed(() =>
    Array.from(new Set(this.cards().map((c) => c.topic).filter(Boolean))).sort(),
  );

  protected readonly visible = computed(() =>
    this.cards()
      .map((card, index) => ({ card, index }))
      .filter((c) => !this.topic() || c.card.topic === this.topic()),
  );

  protected readonly allFlipped = computed(() =>
    this.visible().every((c) => this.flipped().has(c.index)),
  );

  protected flip(i: number): void {
    this.flipped.update((s) => {
      const next = new Set(s);
      next.has(i) ? next.delete(i) : next.add(i);
      return next;
    });
  }

  protected toggleAll(): void {
    const all = this.allFlipped();
    this.flipped.update((s) => {
      const next = new Set(s);
      for (const c of this.visible()) all ? next.delete(c.index) : next.add(c.index);
      return next;
    });
  }
}
