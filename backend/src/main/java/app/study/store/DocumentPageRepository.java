package app.study.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentPageRepository extends JpaRepository<DocumentPage, Long> {

	List<DocumentPage> findByDocumentIdOrderByPageNumber(Long documentId);

	@Transactional
	void deleteByDocumentId(Long documentId);
}
