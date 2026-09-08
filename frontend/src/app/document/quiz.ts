import { Component, computed, input, signal } from '@angular/core';
import { QuizQuestion } from '../core/api/api.service';

type Answer = number | string | null;

/** Self-check quiz: answer everything, then grade with explanations. */
@Component({
  selector: 'app-quiz',
  template: `
    <ol class="questions">
      @for (q of questions(); track $index; let i = $index) {
        <li class="q" [class.correct]="graded() && isCorrect(i)" [class.wrong]="graded() && !isCorrect(i)">
          <div class="q-head">
            <span class="topic">{{ q.topic }}</span>
            <p class="prompt">{{ q.question }}</p>
          </div>

          @if (q.type === 'fill_blank') {
            <input
              type="text"
              class="blank"
              placeholder="Your answer"
              [disabled]="graded()"
              [value]="answers()[i] ?? ''"
              (input)="setAnswer(i, $any($event.target).value)"
            />
          } @else {
            <div class="options">
              @for (opt of q.options; track $index; let j = $index) {
                <label class="option" [class.picked]="answers()[i] === j" [class.answer]="graded() && j === q.correctIndex">
                  <input type="radio" [name]="'q' + i" [value]="j" [disabled]="graded()"
                         [checked]="answers()[i] === j" (change)="setAnswer(i, j)" />
                  <span>{{ opt }}</span>
                </label>
              }
            </div>
          }

          @if (graded()) {
            <p class="explain">
              <strong>{{ isCorrect(i) ? 'Correct.' : 'Not quite.' }}</strong>
              @if (!isCorrect(i)) { Answer: <em>{{ q.options[q.correctIndex] }}</em>. }
              {{ q.explanation }}
            </p>
          }
        </li>
      }
    </ol>

    <div class="footer">
      @if (graded()) {
        <span class="score">Score: {{ score() }} / {{ questions().length }}</span>
        <button type="button" (click)="reset()">Try again</button>
      } @else {
        <button type="button" class="primary" (click)="grade()" [disabled]="answered() < questions().length">
          Check answers ({{ answered() }}/{{ questions().length }} answered)
        </button>
      }
    </div>
  `,
  styles: `
    .questions { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 0.75rem; counter-reset: q; }
    .q { padding: 1rem; border: 1px solid var(--edge); border-radius: 12px; background: var(--surface); counter-increment: q; }
    .q.correct { border-color: #52b788; }
    .q.wrong { border-color: #e07a7a; }
    .q-head { display: flex; flex-direction: column; gap: 0.15rem; margin-bottom: 0.6rem; }
    .topic { font-size: 0.7rem; text-transform: uppercase; letter-spacing: 0.05em; color: var(--ink-dim); }
    .prompt { margin: 0; font-weight: 600; }
    .prompt::before { content: counter(q) '. '; color: var(--ink-dim); font-weight: 500; }
    .options { display: flex; flex-direction: column; gap: 0.35rem; }
    .option { display: flex; gap: 0.5rem; align-items: baseline; padding: 0.4rem 0.6rem; border-radius: 8px; border: 1px solid transparent; cursor: pointer; }
    .option:hover { background: var(--bg); }
    .option.picked { border-color: var(--accent); background: var(--accent-soft, #eef1fc); }
    .option.answer { border-color: #52b788; background: #e6f6ec; }
    .blank { font: inherit; padding: 0.4rem 0.6rem; border-radius: 8px; border: 1px solid var(--edge); width: min(100%, 360px); }
    .explain { margin: 0.75rem 0 0; font-size: 0.9rem; color: var(--ink-dim); }
    .footer { display: flex; gap: 1rem; align-items: center; margin-top: 1rem; }
    .score { font-weight: 700; }
    button { font: inherit; padding: 0.5rem 1rem; border-radius: 8px; border: 1px solid var(--edge); background: var(--surface); cursor: pointer; }
    button.primary { background: var(--accent); color: white; border-color: var(--accent); }
    button:disabled { opacity: 0.5; cursor: not-allowed; }
  `,
})
export class Quiz {
  readonly questions = input.required<QuizQuestion[]>();

  protected readonly answers = signal<Record<number, Answer>>({});
  protected readonly graded = signal(false);

  protected readonly answered = computed(
    () => Object.values(this.answers()).filter((a) => a !== null && a !== '').length,
  );

  protected readonly score = computed(() =>
    this.questions().reduce((n, _, i) => n + (this.isCorrect(i) ? 1 : 0), 0),
  );

  protected setAnswer(i: number, value: Answer): void {
    this.answers.update((a) => ({ ...a, [i]: value }));
  }

  protected isCorrect(i: number): boolean {
    const q = this.questions()[i];
    const a = this.answers()[i];
    if (a === null || a === undefined) return false;
    if (q.type === 'fill_blank') {
      return normalise(String(a)) === normalise(q.options[q.correctIndex] ?? '');
    }
    return a === q.correctIndex;
  }

  protected grade(): void {
    this.graded.set(true);
  }

  protected reset(): void {
    this.answers.set({});
    this.graded.set(false);
  }
}

function normalise(s: string): string {
  return s.trim().toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}
