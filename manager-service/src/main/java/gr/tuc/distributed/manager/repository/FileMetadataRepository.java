package gr.tuc.distributed.manager.repository;

import gr.tuc.distributed.manager.entity.FileMetadata;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FileMetadataRepository extends JpaRepository<FileMetadata, UUID> {

    Optional<FileMetadata> findByFileIdAndUserId(UUID fileId, String userId);

    List<FileMetadata> findByUserIdAndFileType(String userId, String fileType);
}
