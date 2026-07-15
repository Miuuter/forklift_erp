package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.CustomerDTO;
import com.example.forklift_erp.dto.CustomerVO;
import com.example.forklift_erp.entity.Customer;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.CustomerService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.util.ListPageSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class CustomerServiceImpl implements CustomerService {

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private OutboundOrderRepository outboundOrderRepository;

    @Autowired
    private RentalRecordRepository rentalRecordRepository;

    @Autowired
    private RepairRecordRepository repairRecordRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Override
    @Transactional(readOnly = true)
    public List<CustomerVO> findAll() {
        return customerRepository.findAllByOrderByCompanyNameAsc().stream()
                .map(CustomerVO::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<CustomerVO> findPage(String keyword, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<Customer> result = customerRepository.searchPage(
                normalizeKeyword(keyword),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.ASC, "companyName"))
        );
        return PageResult.of(
                result.getContent().stream().map(CustomerVO::fromEntity).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerVO findById(Long id) {
        return customerRepository.findById(id)
                .map(CustomerVO::fromEntity)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Customer not found"));
    }

    @Override
    @Transactional
    public CustomerVO create(CustomerDTO request) {
        ensureUniqueName(request.getCompanyName(), null);
        Customer saved = saveCustomer(request.toEntity());
        operationAuditService.record("Customer", "CREATE", "CUSTOMER", saved.getId(),
                null, saved.getCompanyName(), "Create customer", null, saved.getRemarks());
        return CustomerVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public CustomerVO update(Long id, CustomerDTO request) {
        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Customer not found"));
        collaborationService.validateWrite(customer, request.getVersion());
        ensureUniqueName(request.getCompanyName(), id);
        request.updateEntity(customer);
        Customer saved = saveCustomer(customer);
        operationAuditService.record("Customer", "UPDATE", "CUSTOMER", saved.getId(),
                null, saved.getCompanyName(), "Update customer", null, saved.getRemarks());
        return CustomerVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public void delete(Long id, Long version) {
        Customer customer = customerRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Customer not found"));
        collaborationService.validateWrite(customer, version);
        if (outboundOrderRepository.existsByCustomerId(id)) {
            throw new BusinessException(ResultCode.CONFLICT, "Customer has outbound orders and cannot be deleted");
        }
        if (rentalRecordRepository.existsByCustomerId(id)) {
            throw new BusinessException(ResultCode.CONFLICT, "Customer has rental records and cannot be deleted");
        }
        if (repairRecordRepository.existsByCustomerId(id)) {
            throw new BusinessException(ResultCode.CONFLICT, "Customer has repair records and cannot be deleted");
        }
        customerRepository.delete(customer);
        operationAuditService.record("Customer", "DELETE", "CUSTOMER", id,
                null, customer.getCompanyName(), "Delete customer", null, customer.getRemarks());
    }

    private Customer saveCustomer(Customer customer) {
        collaborationService.stampWrite(customer);
        return customerRepository.saveAndFlush(customer);
    }

    private void ensureUniqueName(String companyName, Long currentId) {
        String normalized = companyName == null ? "" : companyName.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Company name is required");
        }
        boolean duplicated = currentId == null
                ? customerRepository.existsByCompanyName(normalized)
                : customerRepository.existsByCompanyNameAndIdNot(normalized, currentId);
        if (duplicated) {
            throw new BusinessException(ResultCode.DATA_DUPLICATE, "Customer name already exists: " + normalized);
        }
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }
}
