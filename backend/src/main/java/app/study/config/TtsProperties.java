package app.study.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code app.tts.*} for the Kokoro sidecar.
 *
 * @param python       interpreter to launch the sidecar with
 * @param modelPaths   candidate locations for kokoro-v1.0.onnx, first hit wins;
 *                     {@code ~} expands to the home directory
 * @param voicesPaths  candidate locations for voices-v1.0.bin
 * @param modelUrl     where the model can be fetched from when the user asks
 * @param voicesUrl    where the voices file can be fetched from
 * @param defaultVoice Kokoro voice id used unless the request names one
 * @param batchChars   text per synthesis request; keeps progress granular and
 *                     memory bounded on long notes
 */
@ConfigurationProperties(prefix = "app.tts")
public record TtsProperties(
		String python,
		List<String> modelPaths,
		List<String> voicesPaths,
		String modelUrl,
		String voicesUrl,
		String defaultVoice,
		int batchChars) {

	public TtsProperties {
		if (python == null || python.isBlank()) python = "python";
		if (modelPaths == null || modelPaths.isEmpty()) {
			modelPaths = List.of("tools/kokoro/kokoro-v1.0.onnx", "~/kokoro-v1.0.onnx");
		}
		if (voicesPaths == null || voicesPaths.isEmpty()) {
			voicesPaths = List.of("tools/kokoro/voices-v1.0.bin", "~/voices-v1.0.bin");
		}
		if (modelUrl == null || modelUrl.isBlank()) {
			modelUrl = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.onnx";
		}
		if (voicesUrl == null || voicesUrl.isBlank()) {
			voicesUrl = "https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin";
		}
		if (defaultVoice == null || defaultVoice.isBlank()) defaultVoice = "af_heart";
		if (batchChars <= 0) batchChars = 700;
	}
}
