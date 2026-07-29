package com.example.forklift_erp.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(
        name = "resource_attachment",
        indexes = {
                @Index(name = "idx_resource_attachment_resource", columnList = "resource_type, resource_id, attachment_category, deleted, uploaded_at"),
                @Index(name = "idx_resource_attachment_uploaded_at", columnList = "uploaded_at"),
                @Index(name = "idx_resource_attachment_deleted_at", columnList = "deleted_at")
        }
)
public class ResourceAttachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "resource_type", nullable = false, length = 40)
    private String resourceType;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "resource_code", length = 100)
    private String resourceCode;

    @Column(name = "resource_name", length = 120)
    private String resourceName;

    @Column(name = "attachment_category", nullable = false, length = 40)
    private String attachmentCategory;

    @Column(name = "attachment_label", length = 120)
    private String attachmentLabel;

    @Column(name = "storage_scope", nullable = false, length = 40)
    private String storageScope = "ATTACHMENT";

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "stored_file_name", nullable = false, length = 255)
    private String storedFileName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "file_extension", length = 20)
    private String fileExtension;

    @Column(name = "previewable")
    private Boolean previewable = true;

    @Column(name = "upload_note", length = 500)
    private String uploadNote;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    @Column(name = "uploaded_at", updatable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "deleted")
    private Boolean deleted = false;

    @Column(name = "deleted_by", length = 50)
    private String deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "delete_reason", length = 255)
    private String deleteReason;

    @PrePersist
    public void prePersist() {
        if (this.uploadedAt == null) {
            this.uploadedAt = LocalDateTime.now();
        }
        if (this.deleted == null) {
            this.deleted = false;
        }
        if (this.previewable == null) {
            this.previewable = true;
        }
        if (this.storageScope == null || this.storageScope.isBlank()) {
            this.storageScope = "ATTACHMENT";
        }
    }
}
