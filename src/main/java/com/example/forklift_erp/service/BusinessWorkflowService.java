package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.CustomerVO;
import com.example.forklift_erp.dto.MachineInboundPurchaseRequestDTO;
import com.example.forklift_erp.dto.MachineInboundPurchaseVO;
import com.example.forklift_erp.dto.MachineInventoryVO;
import com.example.forklift_erp.dto.OutboundOrderVO;
import com.example.forklift_erp.dto.PurchaseOrderDTO;
import com.example.forklift_erp.dto.PurchaseOrderVO;
import com.example.forklift_erp.dto.VehicleOutboundOrderCreateDTO;
import com.example.forklift_erp.dto.VehicleOutboundWithCustomerRequestDTO;
import com.example.forklift_erp.dto.VehicleOutboundWithCustomerVO;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.exception.BusinessException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

@Service
public class BusinessWorkflowService {
    @Autowired
    private MachineInventoryService machineInventoryService;

    @Autowired
    private PurchaseOrderService purchaseOrderService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private OutboundOrderService outboundOrderService;

    @Autowired
    private Validator validator;

    @Transactional
    public MachineInboundPurchaseVO createMachineInboundPurchase(MachineInboundPurchaseRequestDTO request) {
        PurchaseOrderDTO purchaseOrder = request.getPurchaseOrder();
        if (!PurchaseOrder.RESOURCE_MACHINE.equalsIgnoreCase(purchaseOrder.getResourceType())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Machine inbound workflow requires a MACHINE purchase order");
        }

        // A procurement-created vehicle profile is not inventory yet.  The
        // receipt posting below is the only operation that may create its
        // warehouse balance and FIFO lot.
        request.getInbound().getMachineInventory().setInventoryCount(0);
        request.getInbound().getMachineInventory().setStockStatus(MachineStockStatus.PENDING_INBOUND.code());
        MachineInventoryVO machine = machineInventoryService.inbound(request.getInbound());
        purchaseOrder.setResourceType(PurchaseOrder.RESOURCE_MACHINE);
        purchaseOrder.setResourceId(machine.getId());
        if (purchaseOrder.getWarehouseId() == null) {
            purchaseOrder.setWarehouseId(machine.getWarehouseId());
        }
        purchaseOrder.setResourceCode(machine.getVehicleProductNumber());
        purchaseOrder.setResourceName(machine.getName());
        purchaseOrder.setSpecificationModel(machine.getSpecificationModel());
        PurchaseOrderVO savedOrder = purchaseOrderService.create(purchaseOrder);
        return new MachineInboundPurchaseVO(machine, savedOrder);
    }

    @Transactional
    public VehicleOutboundWithCustomerVO createVehicleOutboundWithCustomer(
            VehicleOutboundWithCustomerRequestDTO request
    ) {
        CustomerVO customer = customerService.create(request.getCustomer());
        VehicleOutboundOrderCreateDTO outbound = request.getOutboundOrder();
        outbound.setCustomerId(customer.getId());
        validate(outbound);
        OutboundOrderVO order = outboundOrderService.createVehicleOutbound(outbound);
        return new VehicleOutboundWithCustomerVO(customer, order);
    }

    private <T> void validate(T value) {
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            throw new ConstraintViolationException(violations);
        }
    }
}
