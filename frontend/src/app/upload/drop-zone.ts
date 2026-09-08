import { Component, ElementRef, input, output, signal, viewChild } from '@angular/core';

/**
 * Drag-and-drop target that also opens a file picker on click. Emits the
 * accepted files; filtering by extension happens here so the parent only
 * ever sees candidates worth uploading.
 */
@Component({
  selector: 'app-drop-zone',
  templateUrl: './drop-zone.html',
  styleUrl: './drop-zone.scss',
  host: {
    '[class.dragging]': 'dragging()',
    '[class.disabled]': 'disabled()',
    '(dragenter)': 'onDragOver($event)',
    '(dragover)': 'onDragOver($event)',
    '(dragleave)': 'onDragLeave($event)',
    '(drop)': 'onDrop($event)',
    '(click)': 'openPicker()',
    role: 'button',
    tabindex: '0',
    '(keydown.enter)': 'openPicker()',
    '(keydown.space)': 'openPicker(); $event.preventDefault()',
  },
})
export class DropZone {
  /** Accepted extensions, lower-case, with the dot. */
  readonly accept = input<string[]>(['.pdf']);
  readonly disabled = input(false);
  readonly files = output<File[]>();
  readonly rejected = output<File[]>();

  protected readonly dragging = signal(false);
  private readonly picker = viewChild.required<ElementRef<HTMLInputElement>>('picker');
  private depth = 0;

  protected acceptAttr(): string {
    return this.accept().join(',');
  }

  protected onDragOver(ev: DragEvent): void {
    ev.preventDefault();
    if (this.disabled()) return;
    if (ev.type === 'dragenter') this.depth++;
    this.dragging.set(true);
  }

  protected onDragLeave(ev: DragEvent): void {
    ev.preventDefault();
    this.depth = Math.max(0, this.depth - 1);
    if (this.depth === 0) this.dragging.set(false);
  }

  protected onDrop(ev: DragEvent): void {
    ev.preventDefault();
    this.depth = 0;
    this.dragging.set(false);
    if (this.disabled()) return;
    this.handle(Array.from(ev.dataTransfer?.files ?? []));
  }

  protected openPicker(): void {
    if (this.disabled()) return;
    this.picker().nativeElement.click();
  }

  protected onPicked(ev: Event): void {
    const input = ev.target as HTMLInputElement;
    this.handle(Array.from(input.files ?? []));
    input.value = '';
  }

  private handle(files: File[]): void {
    if (!files.length) return;
    const ok: File[] = [];
    const bad: File[] = [];
    for (const f of files) {
      const ext = f.name.slice(f.name.lastIndexOf('.')).toLowerCase();
      (this.accept().includes(ext) ? ok : bad).push(f);
    }
    if (ok.length) this.files.emit(ok);
    if (bad.length) this.rejected.emit(bad);
  }
}
