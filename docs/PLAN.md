# Study Assistant — Architecture Outline

A local-first study tool: drop lecture PDFs (later: audio/video), get notes,
key terms, flashcards, and a quiz, then have the app read the notes aloud,
with nothing leaving the machine.

## 1. Stack

| Layer | Choice | Why |
| --- | --- | --- |
| Frontend | Angular 21 (standalone components, signals) | Requested; `ng` 21.2 already installed |
| Backend | Spring Boot 4.1 on Java 25 (Maven wrapper) | Requested; JDK 25 installed (see Concerns re: PATH) |
| PDF text | Apache PDFBox 3.x | Pure Java, no native deps, per-page extraction |
| OCR (optional) | Tesseract via tess4j | Only for image-only slide decks; adds native DLLs |
| LLM | Ollama over its local REST API (`/api/chat`, `/api/embeddings`) | Already installed with `qwen2.5:3b` + `nomic-embed-text` pulled |
| Storage | SQLite via `sqlite-jdbc` + Spring Data JPA | Single file, inspectable, portable |
| TTS tier 0 | Browser `speechSynthesis` (Web Speech API) | Zero install, works day one, uses OS voices |
| TTS tier 1 | Kokoro (`kokoro-onnx`) as a local sidecar spawned by Spring | Already installed in the Python env and `kokoro-v1.0.onnx` is on disk; near-neural quality, CPU-only OK, Apache-2.0 |
| STT (later) | whisper.cpp CLI spawned by Spring, `yt-dlp` for captions | Same approach NitroAI's server uses |
| Run mode | One `java -jar` serving the built Angular app on `localhost:8080`, opens the browser | Simplest "local app"; a desktop shell (Electron/Tauri) can wrap it later |

Everything binds to `127.0.0.1`. The Angular dev server (`:4200`) proxies
`/api/*` to Spring (`:8080`) during development.

## 2. Repository layout

```
study-assistant/
  frontend/                 Angular app
    src/app/
      upload/               drag-drop zone, file list, job progress (SSE)
      library/              list of processed documents
      document/             notes view, key terms, flashcards, quiz tabs
      reader/               TTS player: play/pause, speed, sentence highlight
      core/api/             typed HTTP client for the backend
  backend/                  Spring Boot (mvnw)
    src/main/java/.../
      api/                  REST controllers + SSE progress endpoint
      ingest/               PdfExtractor (PDFBox), OcrExtractor, AudioExtractor
      llm/                  OllamaClient, Chunker, prompt templates,
                            NotesService, FlashcardService, QuizService
      tts/                  TtsEngine interface; KokoroSidecarEngine
      store/                JPA entities: Document, Chunk, Note, Card, Job
      jobs/                 async pipeline runner (ingest -> generate -> persist)
    src/main/resources/
      static/               built Angular output (copied at package time)
      prompts/              *.txt prompt templates, versioned
  tools/                    downloaded binaries + models, GITIGNORED
    kokoro/  whisper/  ffmpeg/  yt-dlp/
  data/                     study.db + uploaded originals + audio cache, GITIGNORED
  docs/PLAN.md
```

## 3. Processing pipeline

```
drop PDF  ->  POST /api/documents (multipart)
          ->  Job created, id returned immediately
          ->  [ingest]   PDFBox page-by-page text (OCR fallback if a page has < N chars)
          ->  [clean]    de-hyphenate, drop headers/footers/slide numbers, join fragments
          ->  [chunk]    ~2-3k-token chunks with overlap (paragraph -> sentence -> hard split)
          ->  [notes]    map: per-chunk section notes / reduce: merge into one Markdown doc
          ->  [extras]   key terms, flashcards, quiz (JSON via Ollama `format: "json"`)
          ->  [persist]  SQLite; originals kept under data/
          ->  UI streams progress over SSE: GET /api/jobs/{id}/events
```

Notes are stored as Markdown and rendered in Angular (marked + KaTeX for math).

## 4. TTS design

`TtsEngine` interface on the backend: `synthesize(text, voice, speed) -> WAV`.

- **Tier 0: Web Speech API (frontend only).** The reader component splits
  notes into sentences, feeds them to `speechSynthesis`, highlights the current
  sentence, exposes rate and voice pickers. No backend involvement. Voice
  quality depends on the OS (Windows here has David and Zira).
- **Tier 1: Kokoro sidecar.** Spring launches a small Python script
  (`tools/kokoro/server.py`) on a local port using the already-installed
  `kokoro-onnx`. Spring posts text, gets WAV back, caches it per note under
  `data/audio/`, and serves it to an `<audio>` element. This enables
  "download the audio and listen on the bus". Needs `kokoro-v1.0.onnx`
  (already at `C:\Users\kmatt\kokoro-v1.0.onnx`) plus `voices-v1.0.bin`
  (~27 MB, one-time download into `tools/kokoro/`).
- Text sent to TTS first goes through a "spoken form" pass (expand
  abbreviations, read equations in words), a prompt to Ollama, same idea as
  NitroAI's `spoken` field.

## 5. Phases

Status as of 2026-09-08.

0. **Scaffold** — done. Angular app, Spring app, proxy config, `/api/health`,
   `.gitignore`, one-jar build that embeds the frontend.
1. **PDF in, text out** — done. Drag-drop, PDFBox extraction (both sort modes,
   per-page pick by garble score), header/footer/page-number/hyphenation
   cleanup, per-page sentence de-duplication for double-layer PDFs, job + SSE
   progress, page/full-text view.
2. **Generation** — done. Hand-rolled Ollama client (streaming, JSON-schema
   structured output, repeat penalty, output caps), chunker, notes
   map/reduce with a frame pass for long documents, key terms, flashcards,
   self-grading quiz, references stripped from source, model picker.
3. **TTS** — done, Kokoro untested pending the 27 MB voices file. Web Speech
   reader with sentence highlighting, speed and voice; "spoken version" pass;
   Kokoro sidecar + WAV export with in-app model download.
4. **Audio/video sources** — done. Drop mp3/mp4/wav or paste a YouTube URL.
   ffmpeg decodes to 16 kHz mono, whisper.cpp transcribes locally, yt-dlp
   fetches captions first and the audio track only when there are none. All
   three tools install themselves into `tools/` on one button press, and
   anything already on PATH is reused. Transcripts are stored in timestamped
   parts so notes sections map to points in the recording.
5. **Chat with the material**: embeddings via `nomic-embed-text`, cosine
   search in Java over stored chunk vectors, grounded answers.
6. **Packaging**: optional Electron/Tauri shell; OCR toggle; settings page.

## 6. Concerns and decisions needed

1. **Java on PATH is 1.8.** `JAVA_HOME` points at JDK 25, but
   `C:\Program Files (x86)\Common Files\Oracle\Java\java8path\java.exe` comes
   first on PATH, so `java -version` says 1.8. The Maven wrapper honours
   `JAVA_HOME`, so builds work, but `java -jar` from a plain shell will fail.
   Fix: put the Adoptium `bin` directory ahead of the Oracle entry in PATH,
   or uninstall JRE 8.
2. **No Maven or Gradle installed.** Not a blocker: Spring Initializr ships
   `mvnw`, which downloads Maven itself.
3. **GPU is a 4 GB RTX 4050.** 3B-4B quantised models fit fully; 7B-8B spill
   to CPU and get slow. `qwen2.5:3b` is a sensible default. Summary quality
   from a 3B model is fine, not great. A model picker is planned.
4. **Slide-deck PDFs are text-poor.** Bullet fragments extract fine; diagrams,
   screenshots, and scanned slides yield nothing without OCR. OCR (tess4j)
   adds ~40 MB of native libs and is slower, so it is proposed as an opt-in
   per-page fallback. Vision models are possible but slow on this GPU.
5. **TTS tier.** Web Speech is instant but sounds robotic; Kokoro sounds good
   but means Spring spawning a Python process. Recommendation: ship both,
   Web Speech first. Windows SAPI via PowerShell is a possible middle tier
   but not cross-platform, so it is skipped unless you want it.
6. **Audio/YouTube needs ffmpeg, yt-dlp, and whisper.cpp; none is installed.**
   The backend would auto-download them into `tools/` on first use, as
   NitroAI does for yt-dlp. Whisper models range from 75 MB (`base.en`) to
   1.5 GB. Proposed as Phase 4, after the PDF path is solid.
7. **Licensing.** NitroAI is AGPL-3.0. Reimplementing its ideas in Java and
   Angular is fine; copying files verbatim would obligate AGPL for this repo.
   The repo has no LICENSE yet; worth picking one.
8. **Spring AI vs a hand-rolled Ollama client.** Spring AI has an Ollama
   starter, but the Ollama API is two endpoints plus streaming NDJSON. A thin
   `RestClient` wrapper is easier to debug and avoids version churn.
   Recommendation: hand-rolled.
9. **Large binaries must never be committed.** `tools/` and `data/` are
   gitignored from the start.
10. **No commits** will be made by the assistant, per your instruction; you
    review and commit.
