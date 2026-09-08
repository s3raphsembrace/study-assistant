package app.study.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Cleaned text of one page (or slide) of a document, in reading order. */
@Entity
@Table(name = "document_pages", indexes = @Index(name = "idx_pages_document", columnList = "documentId,pageNumber"))
public class DocumentPage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false)
	private Long documentId;

	@Column(nullable = false)
	private int pageNumber;

	@Column(columnDefinition = "TEXT", nullable = false)
	private String text;

	protected DocumentPage() {}

	public DocumentPage(Long documentId, int pageNumber, String text) {
		this.documentId = documentId;
		this.pageNumber = pageNumber;
		this.text = text;
	}

	public Long getId() { return id; }
	public Long getDocumentId() { return documentId; }
	public int getPageNumber() { return pageNumber; }
	public String getText() { return text; }
}
