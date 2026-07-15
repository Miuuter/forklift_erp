package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.ResourceAttachmentDownload;
import com.example.forklift_erp.dto.ResourceAttachmentVO;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.ResourceAttachmentService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/attachments")
@PreAuthorize(PermissionCodes.HAS_ATTACHMENT_ACCESS)
public class AttachmentController {

    @Autowired
    private ResourceAttachmentService attachmentService;

    @GetMapping
    public Result<PageResult<ResourceAttachmentVO>> list(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long resourceId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return Result.success(attachmentService.findPage(resourceType, resourceId, category, keyword, includeDeleted, page, size));
    }

    @GetMapping("/resource")
    public Result<List<ResourceAttachmentVO>> byResource(@RequestParam String resourceType, @RequestParam Long resourceId) {
        return Result.success(attachmentService.findByResource(resourceType, resourceId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<List<ResourceAttachmentVO>> upload(
            @RequestParam String resourceType,
            @RequestParam Long resourceId,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String attachmentLabel,
            @RequestParam(required = false) String uploadNote,
            @RequestPart(value = "files", required = false) MultipartFile[] files
    ) {
        return Result.success("附件上传成功", attachmentService.upload(resourceType, resourceId, category, attachmentLabel, uploadNote, files));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        return fileResponse(attachmentService.download(id, false), false);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable Long id) {
        return fileResponse(attachmentService.download(id, true), true);
    }

    @DeleteMapping("/{id}")
    public Result<ResourceAttachmentVO> delete(@PathVariable Long id, @RequestParam(required = false) String reason) {
        return Result.success("附件删除成功", attachmentService.delete(id, reason));
    }

    private ResponseEntity<Resource> fileResponse(ResourceAttachmentDownload attachment, boolean inline) {
        MediaType mediaType = MediaType.APPLICATION_OCTET_STREAM;
        if (attachment.contentType() != null && !attachment.contentType().isBlank()) {
            try {
                mediaType = MediaType.parseMediaType(attachment.contentType());
            } catch (Exception ignored) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
        }
        boolean inlineAllowed = inline && attachment.previewable();
        ContentDisposition disposition = inlineAllowed
                ? ContentDisposition.inline().filename(attachment.originalName(), StandardCharsets.UTF_8).build()
                : ContentDisposition.attachment().filename(attachment.originalName(), StandardCharsets.UTF_8).build();
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(attachment.contentLength())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(attachment.resource());
    }
}
