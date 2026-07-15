package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.dto.DataImportJobVO;
import com.example.forklift_erp.dto.DataImportTemplateFile;
import com.example.forklift_erp.dto.DataImportValidationVO;
import org.springframework.web.multipart.MultipartFile;

public interface DataImportService {
    PageResult<DataImportJobVO> findPage(String importType, String keyword, Integer page, Integer size);

    DataImportJobVO findById(Long id);

    DataImportTemplateFile template(String importType);

    DataImportValidationVO validate(String importType, String importMode, MultipartFile file);

    DataImportValidationVO confirm(Long jobId);
}
