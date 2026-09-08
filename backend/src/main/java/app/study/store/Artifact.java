package app.study.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Something generated from a document: Markdown notes, or JSON for the
 * structured kinds. One row per (document, kind); regenerating replaces it.
 */
@Entity
@Table(name = "artifacts", uniqueConstraints = @UniqueConstraint(columnNames = { "documentId", "kind" }))
public class Artifact {

	public enum Kind {
		/** Markdown study notes. */
		NOTES,
		/** JSON: {"terms":[{"term","definition"}]} */
		KEY_TERMS,
		/** JSON: {"cards":[{"front","back","topic"}]} */
		FLASHCARDS,
		/** JSON: {"questions":[{"type","topic","question","options","correctIndex","explanation"}]} */
		QUIZ,
		/** Plain text rewrite of the notes for text-to-speech. */
		SPOKEN,
		/** JSON pointer to a synthesized WAV: {"path","voice","speed","sampleRate","bytes","seconds"} */
		AUDIO;

		public boolean isJson() {
			return this == KEY_TERMS || this == FLASHCARDS || this == QUIZ || this == AUDIO;
		}
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long documentId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Kind kind;

	@Column(columnDefinition = "TEXT", nullable = false)
	private String content;

	/** Model tag that produced it, for the record. */
	private String model;

	@Column(nullable = false)
	private Instant createdAt;

	protected Artifact() {}

	public Artifact(Long documentId, Kind kind, String content, String model) {
		this.documentId = documentId;
		this.kind = kind;
		this.content = content;
		this.model = model;
		this.createdAt = Instant.now();
	}

	public Long getId() { return id; }
	public Long getDocumentId() { return documentId; }
	public Kind getKind() { return kind; }
	public String getContent() { return content; }
	public String getModel() { return model; }
	public Instant getCreatedAt() { return createdAt; }

	public void replace(String content, String model) {
		this.content = content;
		this.model = model;
		this.createdAt = Instant.now();
	}
}
