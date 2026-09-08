package app.study.store;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<Document, Long> {

	List<Document> findAllByOrderByCreatedAtDesc();
}
