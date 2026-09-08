package app.study.store;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** One piece of study material the user dropped in (a PDF for now). */
@Entity
@Table(name = "documents")
public class Document {

	public enum SourceKind { PDF, AUDIO, YOUTUBE }

	public enum Status { QUEUED, EXTRACTING, READY, ERROR }

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private String title;

	@Column(nullable = false)
	private String filename;

	/** Absolute path of the stored original under data/uploads. */
	private String storedPath;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SourceKind sourceKind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Status status;

	private Integer pageCount;
	private Integer charCount;

	@Column(columnDefinition = "TEXT")
	private String error;

	@Column(nullable = false)
	private Instant createdAt;

	@Column(nullable = false)
	private Instant updatedAt;

	@PrePersist
	void onCreate() {
		Instant now = Instant.now();
		if (createdAt == null) createdAt = now;
		updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		updatedAt = Instant.now();
	}

	public Long getId() { return id; }
	public String getTitle() { return title; }
	public void setTitle(String title) { this.title = title; }
	public String getFilename() { return filename; }
	public void setFilename(String filename) { this.filename = filename; }
	public String getStoredPath() { return storedPath; }
	public void setStoredPath(String storedPath) { this.storedPath = storedPath; }
	public SourceKind getSourceKind() { return sourceKind; }
	public void setSourceKind(SourceKind sourceKind) { this.sourceKind = sourceKind; }
	public Status getStatus() { return status; }
	public void setStatus(Status status) { this.status = status; }
	public Integer getPageCount() { return pageCount; }
	public void setPageCount(Integer pageCount) { this.pageCount = pageCount; }
	public Integer getCharCount() { return charCount; }
	public void setCharCount(Integer charCount) { this.charCount = charCount; }
	public String getError() { return error; }
	public void setError(String error) { this.error = error; }
	public Instant getCreatedAt() { return createdAt; }
	public Instant getUpdatedAt() { return updatedAt; }
}
