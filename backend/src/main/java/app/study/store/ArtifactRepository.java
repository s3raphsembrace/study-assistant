package app.study.store;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface ArtifactRepository extends JpaRepository<Artifact, Long> {

	Optional<Artifact> findByDocumentIdAndKind(Long documentId, Artifact.Kind kind);

	List<Artifact> findByDocumentId(Long documentId);

	@Transactional
	void deleteByDocumentId(Long documentId);
}
