package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.Customer;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.CustomerRepository;
import org.springframework.stereotype.Service;

@Service
public class OutboundCustomerService {
    private final CustomerRepository customerRepository;

    public OutboundCustomerService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public void copyCustomer(OutboundOrder order, Long customerId) {
        Customer customer = customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Customer not found"));
        order.setCustomerId(customer.getId());
        order.setCustomerName(customer.getCompanyName());
        order.setCustomerAddress(customer.getAddress());
        order.setContactName(customer.getContactName());
        order.setContactPhone(customer.getContactPhone());
        order.setTaxOrIdNumber(customer.getTaxOrIdNumber());
    }
}
