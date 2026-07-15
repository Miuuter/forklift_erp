package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.SupplierDTO;
import com.example.forklift_erp.dto.SupplierVO;
import com.example.forklift_erp.entity.Supplier;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.SupplierRepository;
import com.example.forklift_erp.util.ListPageSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SupplierService {
    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private MachineInventoryRepository machineInventoryRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Transactional(readOnly = true)
    public List<SupplierVO> findAll() {
        return supplierRepository.findAllByOrderBySupplierNameAsc().stream()
                .map(SupplierVO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<SupplierVO> findPage(String keyword, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<Supplier> result = supplierRepository.searchPage(
                normalizeKeyword(keyword),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.ASC, "supplierName"))
        );
        return PageResult.of(
                result.getContent().stream().map(SupplierVO::fromEntity).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Transactional
    public SupplierVO create(SupplierDTO request) {
        ensureUniqueName(request.getSupplierName(), null);
        Supplier supplier = new Supplier();
        copy(request, supplier);
        collaborationService.stampWrite(supplier);
        Supplier saved = supplierRepository.saveAndFlush(supplier);
        operationAuditService.record("Supplier", "CREATE", "SUPPLIER", saved.getId(),
                null, saved.getSupplierName(), "Create supplier", null, saved.getRemarks());
        return SupplierVO.fromEntity(saved);
    }

    @Transactional
    public SupplierVO update(Long id, SupplierDTO request) {
        Supplier supplier = supplierRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Supplier not found"));
        collaborationService.validateWrite(supplier, request.getVersion());
        ensureUniqueName(request.getSupplierName(), id);
        copy(request, supplier);
        collaborationService.stampWrite(supplier);
        Supplier saved = supplierRepository.saveAndFlush(supplier);
        operationAuditService.record("Supplier", "UPDATE", "SUPPLIER", saved.getId(),
                null, saved.getSupplierName(), "Update supplier", null, saved.getRemarks());
        return SupplierVO.fromEntity(saved);
    }

    @Transactional
    public void delete(Long id, Long version) {
        Supplier supplier = supplierRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Supplier not found"));
        collaborationService.validateWrite(supplier, version);
        supplier.setActive(false);
        collaborationService.stampWrite(supplier);
        supplierRepository.saveAndFlush(supplier);
        String summary = (purchaseOrderRepository.existsBySupplierId(id) || machineInventoryRepository.existsBySupplierId(id))
                ? "Deactivate supplier with historical references"
                : "Deactivate supplier";
        operationAuditService.record("Supplier", "DEACTIVATE", "SUPPLIER", id,
                null, supplier.getSupplierName(), summary, null, supplier.getRemarks());
    }

    private void copy(SupplierDTO request, Supplier supplier) {
        supplier.setSupplierName(blankToNull(request.getSupplierName()));
        supplier.setSupplierType(blankToNull(request.getSupplierType()));
        if (request.getActive() != null) {
            supplier.setActive(request.getActive());
        } else if (supplier.getActive() == null) {
            supplier.setActive(true);
        }
        supplier.setContactName(blankToNull(request.getContactName()));
        supplier.setContactPhone(blankToNull(request.getContactPhone()));
        supplier.setAddress(blankToNull(request.getAddress()));
        supplier.setTaxNumber(blankToNull(request.getTaxNumber()));
        supplier.setBankAccount(blankToNull(request.getBankAccount()));
        supplier.setRemarks(blankToNull(request.getRemarks()));
    }

    private void ensureUniqueName(String name, Long currentId) {
        String normalized = blankToNull(name);
        if (normalized == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Supplier name is required");
        }
        boolean duplicated = currentId == null
                ? supplierRepository.existsBySupplierName(normalized)
                : supplierRepository.existsBySupplierNameAndIdNot(normalized, currentId);
        if (duplicated) {
            throw new BusinessException(ResultCode.DATA_DUPLICATE, "Supplier name already exists: " + normalized);
        }
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
