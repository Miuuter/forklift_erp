package com.example.forklift_erp.controller;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.Result;
import com.example.forklift_erp.dto.DataImportJobVO;
import com.example.forklift_erp.dto.DataImportTemplateFile;
import com.example.forklift_erp.dto.DataImportValidationVO;
import com.example.forklift_erp.security.PermissionCodes;
import com.example.forklift_erp.service.DataImportService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/imports")
@PreAuthorize(PermissionCodes.HAS_SUPER_ADMIN)
public class DataImportController {

    private static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @Autowired
    private DataImportService importService;

    @GetMapping
    public Result<PageResult<DataImportJobVO>> list(
            @RequestParam(required = false) String importType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size
    ) {
        return Result.success(importService.findPage(importType, keyword, page, size));
    }

    @GetMapping("/{id}")
    public Result<DataImportJobVO> get(@PathVariable Long id) {
        return Result.success(importService.findById(id));
    }

    @GetMapping("/templates/{type}")
    public ResponseEntity<ByteArrayResource> template(@PathVariable String type) {
        DataImportTemplateFile file = importService.template(type);
        return ResponseEntity.ok()
                .contentType(XLSX_MEDIA_TYPE)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(new ByteArrayResource(file.content()));
    }

    @PostMapping(value = "/{type}/validate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result<DataImportValidationVO> validate(
            @PathVariable String type,
            @RequestParam(required = false) String mode,
            @RequestParam("file") MultipartFile file
    ) {
        return Result.success("导入文件校验完成", importService.validate(type, mode, file));
    }

    @PostMapping("/{id}/confirm")
    public Result<DataImportValidationVO> confirm(@PathVariable Long id) {
        return Result.success("导入完成", importService.confirm(id));
    }
}
