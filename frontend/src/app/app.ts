import { Component, inject, signal } from '@angular/core';
import { RouterOutlet } from '@angular/router';
import { ApiService, Settings, errorMessage } from './core/api/api.service';

type BackendState = 'checking' | 'online' | 'offline';

@Component({
  selector: 'app-root',
  imports: [RouterOutlet],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly api = inject(ApiService);

  protected readonly backend = signal<BackendState>('checking');
  protected readonly settings = signal<Settings | null>(null);
  protected readonly settingsError = signal<string | null>(null);

  constructor() {
    this.api.health().subscribe({
      next: (h) => {
        this.backend.set(h.ok ? 'online' : 'offline');
        if (h.ok) this.loadSettings();
      },
      error: () => this.backend.set('offline'),
    });
  }

  protected loadSettings(): void {
    this.api.getSettings().subscribe({
      next: (s) => this.settings.set(s),
      error: (e) => this.settingsError.set(errorMessage(e)),
    });
  }

  protected setModel(model: string): void {
    this.api.updateSettings({ chatModel: model }).subscribe({
      next: (s) => this.settings.set(s),
      error: (e) => this.settingsError.set(errorMessage(e)),
    });
  }

  /** Models to offer: everything pulled, plus the current one even if it isn't pulled yet. */
  protected modelOptions(s: Settings): string[] {
    const list = [...s.ollama.models];
    if (!list.includes(s.chatModel)) list.unshift(s.chatModel);
    return list;
  }
}
