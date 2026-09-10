package app.study.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code app.media.*}: the tools that turn audio, video and
 * YouTube links into text. All three are optional downloads, fetched only
 * when the user asks for them.
 *
 * @param whisperModel   ggml model name, e.g. {@code base.en} (148 MB) or {@code small.en} (488 MB)
 * @param whisperBinUrl  zip of the whisper.cpp Windows build
 * @param whisperModelUrl download URL, {@code {model}} is replaced by whisperModel
 * @param ytdlpUrl       yt-dlp executable
 * @param ffmpegUrl      ffmpeg zip (only ffmpeg.exe/ffprobe.exe are kept)
 * @param language       transcription language code, or {@code auto} to detect
 * @param threads        whisper threads; 0 means half the available cores
 * @param segmentSeconds transcript is stored in parts of about this length
 */
@ConfigurationProperties(prefix = "app.media")
public record MediaProperties(
		String whisperModel,
		String whisperBinUrl,
		String whisperModelUrl,
		String ytdlpUrl,
		String ffmpegUrl,
		String language,
		int threads,
		int segmentSeconds) {

	public MediaProperties {
		if (whisperModel == null || whisperModel.isBlank()) whisperModel = "base.en";
		if (whisperBinUrl == null || whisperBinUrl.isBlank()) {
			whisperBinUrl = "https://github.com/ggml-org/whisper.cpp/releases/download/b4938/whisper-blas-bin-x64.zip";
		}
		if (whisperModelUrl == null || whisperModelUrl.isBlank()) {
			whisperModelUrl = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-{model}.bin";
		}
		if (ytdlpUrl == null || ytdlpUrl.isBlank()) {
			ytdlpUrl = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp.exe";
		}
		if (ffmpegUrl == null || ffmpegUrl.isBlank()) {
			ffmpegUrl = "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip";
		}
		if (language == null || language.isBlank()) language = "en";
		if (threads <= 0) threads = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
		if (segmentSeconds <= 0) segmentSeconds = 120;
	}

	public String modelFileName() {
		return "ggml-" + whisperModel + ".bin";
	}

	public String resolvedModelUrl() {
		return whisperModelUrl.replace("{model}", whisperModel);
	}
}
