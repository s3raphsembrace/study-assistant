# Study Assistant

Drop lecture PDFs, get study notes, key terms, flashcards and a quiz, then have
them read aloud. Everything runs on your machine: text extraction (PDFBox),
generation (Ollama) and text-to-speech (browser voices or Kokoro) never leave
localhost.

See [docs/PLAN.md](docs/PLAN.md) for the architecture and roadmap.

## What works today

- Drag-and-drop one or more PDFs; text is extracted page by page with running
  headers, slide numbers and hyphenation cleaned up. Live progress per file.
- Drop an audio or video recording, or paste a YouTube lecture link. YouTube
  captions are used when the video has them; otherwise the audio is
  transcribed locally with whisper.cpp.
- Notes are generated automatically after extraction (map/reduce over chunks
  for long documents), then key terms, flashcards and a self-grading quiz on
  demand from the document page. Math renders with KaTeX. Each section of the
  notes shows the slides it was written from.
- **Listen** tab: reads the notes aloud with the browser's built-in voices
  (sentence highlighting, speed, voice picker), or with Kokoro for a natural
  voice and a downloadable WAV. A "spoken version" pass rewrites math and
  abbreviations into words first.
- Model picker in the top bar (any model pulled into Ollama).

## Layout

```
frontend/   Angular 21 app
backend/    Spring Boot 4.1 (Java 25): REST API, ingestion, generation, TTS
tools/      downloaded binaries and models (gitignored, created on demand)
data/       SQLite db, uploaded originals, audio cache (gitignored)
```

## Prerequisites

- JDK 25 (`JAVA_HOME` must point at it; the Maven wrapper uses `JAVA_HOME`)
- Node 20.19+ and npm
- [Ollama](https://ollama.com) running locally with a chat model pulled
  (default `qwen2.5:3b`):

```bash
ollama pull qwen2.5:3b
```

Optional, for the natural voice: Python 3 with `pip install kokoro-onnx`. The
app then offers a one-click download of the Kokoro model files (about 340 MB)
into `tools/kokoro/`; an existing `kokoro-v1.0.onnx` / `voices-v1.0.bin` in
your home folder is picked up automatically.

Optional, for audio, video and YouTube: nothing to install by hand. The
library page offers an "Install media tools" button that downloads ffmpeg,
whisper.cpp with its `base.en` speech model, and yt-dlp (about 298 MB total)
into `tools/`. Anything already on your PATH is used instead of downloading.

## Run the packaged app

Builds the Angular app, embeds it in the jar, runs the tests, and produces
`backend/target/study-assistant.jar`:

```bash
cd backend && ./mvnw package
```

Then, from the repo root (so `data/` and `tools/` land here):

```bash
java -jar backend/target/study-assistant.jar
```

It listens on `http://127.0.0.1:8080/` and opens your browser. Useful flags:

| Flag | Effect |
| --- | --- |
| `--app.open-browser=false` | don't launch a browser |
| `--app.data-dir=/some/path` | put the database and uploads elsewhere |
| `--server.port=9090` | change the port |
| `--app.ollama.chat-model=llama3.2:3b` | default model (also changeable in the UI) |

## Develop

Two terminals. Backend with the dev profile (no browser launch, data in the
repo-root `data/`):

```bash
cd backend && ./mvnw spring-boot:run -DskipFrontend -Dspring-boot.run.profiles=dev
```

Frontend dev server on `http://127.0.0.1:4200/`, proxying `/api` to `:8080`:

```bash
cd frontend && npm start
```

Other commands:

```bash
cd backend && ./mvnw test -DskipFrontend     # backend tests
cd frontend && npm test                      # frontend tests (vitest)
cd frontend && npm run build                 # production bundle to frontend/dist
```

`-DskipFrontend` skips the npm install/build step that the `package` lifecycle
runs by default.

## Tuning

All knobs live in `backend/src/main/resources/application.properties` and can
be overridden on the command line:

| Property | Default | Meaning |
| --- | --- | --- |
| `app.ollama.num-ctx` | 8192 | context window requested per call |
| `app.ollama.chunk-tokens` | 3000 | input budget per generation call |
| `app.ollama.repeat-penalty` | 1.1 | keeps small models out of repetition loops |
| `app.generation.auto-notes` | true | generate notes right after extraction |
| `app.generation.language` | English | language of generated material |
| `app.tts.default-voice` | af_heart | Kokoro voice id |
| `app.media.whisper-model` | base.en | speech model; `small.en` is slower and better |
| `app.media.language` | en | transcription language, or `auto` to detect |
| `app.media.threads` | 0 | whisper threads; 0 means half your cores |

## Windows note

If `java -version` prints 1.8 while `javac -version` prints 25, an old Oracle
JRE 8 entry sits ahead of the JDK on your PATH. Either move
`C:\Program Files\Eclipse Adoptium\jdk-25.0.2.10-hotspot\bin` above it, or run
the jar with the full path: `"%JAVA_HOME%\bin\java" -jar ...`.
