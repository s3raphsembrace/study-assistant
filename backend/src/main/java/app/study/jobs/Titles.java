package app.study.jobs;

/** The one place that derives a display title from an uploaded filename. */
public final class Titles {

	private Titles() {}

	public static String fromFilename(String filename) {
		String t = filename.replaceAll("\\.[^.]+$", "").replace('_', ' ').trim();
		return t.isEmpty() ? "Untitled" : (t.length() > 80 ? t.substring(0, 80) : t);
	}
}
