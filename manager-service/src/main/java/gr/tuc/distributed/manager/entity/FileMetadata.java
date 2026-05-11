package gr.tuc.distributed.manager.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "file_metadata")
@Getter
@Setter
@NoArgsConstructor
public class FileMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "user_id", nullable = false)
    private String userId;

    /** "DATA" or "CODE" */
    @Column(name = "file_type", nullable = false)
    private String fileType;

    @Column(name = "original_name", nullable = false)
    private String originalName;

    @Column(name = "storage_path", nullable = false)
    private String storagePath;

    @Column(name = "size_bytes")
    private Long sizeBytes;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
}
