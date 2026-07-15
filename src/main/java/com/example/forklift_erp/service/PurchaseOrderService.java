package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.dto.PurchaseOrderDTO;
import com.example.forklift_erp.dto.PurchaseOrderVO;
import com.example.forklift_erp.entity.ConfigItem;
import com.example.forklift_erp.entity.ConfigValue;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.PaymentRecord;
import com.example.forklift_erp.entity.PurchaseOrder;
import com.example.forklift_erp.entity.StockLot;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.Supplier;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigItemRepository;
import com.example.forklift_erp.repository.ConfigValueRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PaymentRecordRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.SupplierRepository;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.MoneyValues;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Service
public class PurchaseOrderService {
    private static final String STATUS_ORDERED = "ORDERED";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_ARRIVED = "ARRIVED";
    private static final String STATUS_RECEIVED = "RECEIVED";
    private static final String STATUS_CANCELED = "CANCELED";

    @Autowired
    private PurchaseOrderRepository purchaseOrderRepository;

    @Autowired
    private SupplierRepository supplierRepository;

    @Autowired
    private ConfigItemRepository configItemRepository;

    @Autowired
    private ConfigValueRepository configValueRepository;

    @Autowired
    private CollaborationService collaborationService;

    @Autowired
    private OperationAuditService operationAuditService;

    @Autowired
    private StockLedgerService stockLedgerService;

    @Autowired
    private StockLotService stockLotService;

    @Autowired
    private FinancialEventService financialEventService;

    @Autowired
    private PartInventoryRepository partInventoryRepository;

    @Autowired
    private MachineInventoryRepository machineInventoryRepository;

    @Autowired
    private PaymentRecordRepository paymentRecordRepository;

    @Transactional(readOnly = true)
    public List<PurchaseOrderVO> findAll() {
        return purchaseOrderRepository.findAll(Sort.by(Sort.Direction.DESC, "createdAt")).stream()
                .map(PurchaseOrderVO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResult<PurchaseOrderVO> findPage(String keyword, Integer page, Integer size) {
        return findPage(keyword, null, page, size);
    }

    @Transactional(readOnly = true)
    public PageResult<PurchaseOrderVO> findPage(String keyword, String resourceType, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<PurchaseOrder> result = purchaseOrderRepository.searchPage(
                normalizeKeyword(keyword),
                normalizeResourceTypeFilter(resourceType),
                ListPageSupport.pageRequest(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        );
        return PageResult.of(
                result.getContent().stream().map(PurchaseOrderVO::fromEntity).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    @Transactional
    public PurchaseOrderVO create(PurchaseOrderDTO request) {
        PurchaseOrder order = new PurchaseOrder();
        order.setPurchaseNo(nextPurchaseNo());
        copy(request, order);
        collaborationService.stampWrite(order);
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        if (STATUS_RECEIVED.equals(saved.getStatus())) {
            postReceiptIfLinked(saved);
            saved = purchaseOrderRepository.saveAndFlush(saved);
        }
        operationAuditService.record("Purchase order", "CREATE", "PURCHASE_ORDER", saved.getId(),
                saved.getPurchaseNo(), saved.getSupplierName(), "Create purchase order", saved.getOperator(), saved.getRemark());
        return PurchaseOrderVO.fromEntity(saved);
    }

    @Transactional
    public PurchaseOrderVO update(Long id, PurchaseOrderDTO request) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Purchase order not found"));
        collaborationService.validateWrite(order, request.getVersion());
        if (order.getReceivedStockMovementId() != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A received purchase order is immutable; reverse receipt before changing procurement details");
        }
        copy(request, order);
        collaborationService.stampWrite(order);
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        if (STATUS_RECEIVED.equals(saved.getStatus())) {
            postReceiptIfLinked(saved);
            saved = purchaseOrderRepository.saveAndFlush(saved);
        }
        operationAuditService.record("Purchase order", "UPDATE", "PURCHASE_ORDER", saved.getId(),
                saved.getPurchaseNo(), saved.getSupplierName(), "Update purchase order", saved.getOperator(), saved.getRemark());
        return PurchaseOrderVO.fromEntity(saved);
    }

    @Transactional
    public void delete(Long id, Long version) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Purchase order not found"));
        collaborationService.validateWrite(order, version);
        if (order.getReceivedStockMovementId() != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A received purchase order must be reversed before deletion");
        }
        if (paymentRecordRepository.existsBySourceTypeAndSourceId(
                FinancialEventService.SOURCE_PURCHASE_ORDER, order.getId())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "A purchase order with payment history cannot be deleted");
        }
        purchaseOrderRepository.delete(order);
        operationAuditService.record("Purchase order", "DELETE", "PURCHASE_ORDER", id,
                order.getPurchaseNo(), order.getSupplierName(), "Delete purchase order", order.getOperator(), order.getRemark());
    }

    @Transactional
    public PurchaseOrderVO setReceived(Long id, boolean received, Long version) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Purchase order not found"));
        collaborationService.validateWrite(order, version);
        if (received) {
            markReceived(order);
            postReceiptIfLinked(order);
        } else {
            reverseReceiptIfPosted(order);
            restoreStatusBeforeReceived(order);
        }
        if (order.getFreightAmount() == null) {
            order.setFreightAmount(BigDecimal.ZERO);
        }
        collaborationService.stampWrite(order);
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        operationAuditService.record("Purchase order", received ? STATUS_RECEIVED : saved.getStatus(), "PURCHASE_ORDER", saved.getId(),
                saved.getPurchaseNo(), saved.getSupplierName(), received ? "Mark purchase received" : "Mark purchase not received",
                saved.getOperator(), saved.getRemark());
        return PurchaseOrderVO.fromEntity(saved);
    }

    @Transactional
    public PurchaseOrderVO updateFreight(Long id, BigDecimal freightAmount, Long version) {
        PurchaseOrder order = purchaseOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Purchase order not found"));
        collaborationService.validateWrite(order, version);
        if (order.getReceivedStockMovementId() != null) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Freight is part of landed cost; reverse receipt before changing it");
        }
        BigDecimal normalizedFreight = MoneyValues.zeroIfNegative(freightAmount);
        order.setFreightAmount(normalizedFreight == null ? BigDecimal.ZERO : normalizedFreight);
        collaborationService.stampWrite(order);
        PurchaseOrder saved = purchaseOrderRepository.saveAndFlush(order);
        operationAuditService.record("Purchase order", "FREIGHT_UPDATE", "PURCHASE_ORDER", saved.getId(),
                saved.getPurchaseNo(), saved.getSupplierName(), "Update purchase freight", saved.getOperator(), saved.getRemark());
        return PurchaseOrderVO.fromEntity(saved);
    }

    private void copy(PurchaseOrderDTO request, PurchaseOrder order) {
        String resourceType = normalizeResourceType(request.getResourceType());
        Supplier supplier = resolveSupplier(request, order, resourceType);
        ConfigItem configItem = null;
        ConfigValue configValue = null;
        PartInventory selectedPart = null;
        MachineInventory selectedMachine = null;
        if (PurchaseOrder.RESOURCE_PART.equals(resourceType)) {
            configItem = request.getConfigItemId() == null ? null : configItemRepository.findById(request.getConfigItemId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config item not found"));
            configValue = request.getConfigValueId() == null ? null : configValueRepository.findById(request.getConfigValueId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config value not found"));
            if (configValue != null && configItem != null && !configValue.getConfigItemId().equals(configItem.getId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Config value does not belong to selected config item");
            }
            if (configValue != null && configItem == null) {
                configItem = configItemRepository.findById(configValue.getConfigItemId())
                        .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Config item not found"));
            }
            Long resourceId = request.getResourceId() == null ? order.getResourceId() : request.getResourceId();
            if (resourceId == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Part purchase order must select an actual part SKU");
            }
            selectedPart = partInventoryRepository.findById(resourceId)
                    .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Purchase part SKU not found"));
        }
        if (PurchaseOrder.RESOURCE_MACHINE.equals(resourceType)) {
            Long resourceId = request.getResourceId() == null ? order.getResourceId() : request.getResourceId();
            if (resourceId == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Machine purchase order must select a concrete vehicle resource");
            }
            selectedMachine = machineInventoryRepository.findById(resourceId)
                    .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Purchase vehicle not found"));
            if (Boolean.TRUE.equals(selectedMachine.getModelOnly())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Vehicle model template cannot be used as a purchase receipt resource");
            }
        }

        order.setSupplierId(supplier.getId());
        order.setSupplierName(supplier.getSupplierName());
        order.setConfigItemId(PurchaseOrder.RESOURCE_PART.equals(resourceType) && configItem != null ? configItem.getId() : null);
        order.setConfigValueId(PurchaseOrder.RESOURCE_PART.equals(resourceType) && configValue != null ? configValue.getId() : null);
        order.setResourceType(resourceType);
        order.setWarehouseId(request.getWarehouseId());
        order.setResourceId(PurchaseOrder.RESOURCE_PART.equals(resourceType) ? selectedPart.getId() : selectedMachine.getId());
        order.setResourceCode(PurchaseOrder.RESOURCE_PART.equals(resourceType)
                ? selectedPart.getPartCode()
                : selectedMachine.getVehicleProductNumber());
        order.setResourceName(PurchaseOrder.RESOURCE_PART.equals(resourceType)
                ? selectedPart.getPartName()
                : selectedMachine.getName());
        order.setSpecificationModel(PurchaseOrder.RESOURCE_PART.equals(resourceType)
                ? selectedPart.getSpecification()
                : selectedMachine.getSpecificationModel());
        order.setQuantity(request.getQuantity() == null ? 1 : request.getQuantity());
        String requestUnit = blankToNull(request.getUnit());
        if (requestUnit == null && selectedPart != null) {
            requestUnit = blankToNull(selectedPart.getUnit());
        }
        if (requestUnit == null && PurchaseOrder.RESOURCE_MACHINE.equals(resourceType)) {
            requestUnit = "台";
        }
        order.setUnit(requestUnit);
        order.setUnitPrice(MoneyValues.zeroIfNegative(request.getUnitPrice()));
        order.setTotalAmount(totalAmount(order.getQuantity(), order.getUnitPrice(), request.getTotalAmount()));
        if (request.getFreightAmount() != null) {
            order.setFreightAmount(MoneyValues.zeroIfNegative(request.getFreightAmount()));
        } else if (order.getFreightAmount() == null) {
            order.setFreightAmount(BigDecimal.ZERO);
        }
        order.setOrderDate(request.getOrderDate() == null ? LocalDate.now() : request.getOrderDate());
        order.setExpectedArrivalDate(request.getExpectedArrivalDate());
        if (request.getReceivedDate() != null) {
            order.setReceivedDate(request.getReceivedDate());
        }
        applyRequestedStatus(order, request.getStatus());
        order.setOperator(blankToNull(request.getOperator()));
        order.setRemark(blankToNull(request.getRemark()));
    }

    private void applyRequestedStatus(PurchaseOrder order, String requestedStatus) {
        String nextStatus = blankToNull(requestedStatus);
        if (nextStatus == null) {
            nextStatus = STATUS_ORDERED;
        }
        String currentStatus = blankToNull(order.getStatus());
        if (currentStatus == null) {
            currentStatus = STATUS_ORDERED;
        }

        if (STATUS_RECEIVED.equals(nextStatus)) {
            if (!STATUS_RECEIVED.equals(currentStatus)) {
                markReceived(order);
            }
            return;
        }
        if (STATUS_CANCELED.equals(nextStatus) && order.getId() != null) {
            BigDecimal paid = paymentRecordRepository.totalForSource(
                    FinancialEventService.SOURCE_PURCHASE_ORDER,
                    order.getId(),
                    PaymentRecord.DIRECTION_PAYMENT
            );
            if (paid != null && paid.signum() != 0) {
                throw new BusinessException(ResultCode.CONFLICT,
                        "A paid purchase order cannot be canceled until its payments are reversed");
            }
        }

        order.setStatus(nextStatus);
        order.setStatusBeforeReceived(null);
    }

    private void markReceived(PurchaseOrder order) {
        String currentStatus = blankToNull(order.getStatus());
        if (STATUS_RECEIVED.equals(currentStatus)) {
            return;
        }
        if (STATUS_CANCELED.equals(currentStatus)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Canceled purchase order cannot be received");
        }
        if (!isReceivableStatus(currentStatus)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Purchase order status cannot be marked received: " + currentStatus);
        }
        order.setStatusBeforeReceived(currentStatus);
        order.setStatus(STATUS_RECEIVED);
    }

    private void restoreStatusBeforeReceived(PurchaseOrder order) {
        if (!STATUS_RECEIVED.equals(order.getStatus())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Only received purchase orders can undo receipt");
        }
        String previousStatus = blankToNull(order.getStatusBeforeReceived());
        order.setStatus(isReceivableStatus(previousStatus) ? previousStatus : STATUS_ORDERED);
        order.setStatusBeforeReceived(null);
    }

    private boolean isReceivableStatus(String status) {
        return STATUS_ORDERED.equals(status) || STATUS_PARTIAL.equals(status) || STATUS_ARRIVED.equals(status);
    }

    private void postReceiptIfLinked(PurchaseOrder order) {
        if (order.getReceivedStockMovementId() != null) {
            return;
        }
        if (order.getResourceId() == null) {
            // Historical status-only orders are retained for compatibility. They
            // intentionally do not create guessed inventory or financial facts.
            return;
        }
        Long warehouseId = stockLedgerService.resolveWarehouseId(order.getWarehouseId());
        order.setWarehouseId(warehouseId);
        LocalDate receivedDate = order.getReceivedDate() == null ? LocalDate.now() : order.getReceivedDate();
        order.setReceivedDate(receivedDate);
        int quantity = order.getQuantity() == null ? 0 : order.getQuantity();
        if (quantity < 1) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Received quantity must be greater than 0");
        }
        BigDecimal landedCost = landedUnitCost(order);
        BigDecimal payable = purchasePayable(order);

        if (PurchaseOrder.RESOURCE_PART.equals(order.getResourceType())) {
            PartInventory part = partInventoryRepository.findByIdForUpdate(order.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Purchase part SKU not found"));
            int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_PART, part.getId(), warehouseId);
            StockLot lot = stockLotService.createReceiptLot(
                    StockLedgerService.RESOURCE_PART, part.getId(), warehouseId, quantity, landedCost,
                    MoneyValues.zeroIfNullOrNegative(order.getFreightAmount()), "PURCHASE_ORDER", order.getId(), null,
                    receivedDate, "PURCHASE-LOT:" + order.getId()
            );
            StockMovement movement = stockLedgerService.recordMovement(
                    "INBOUND", StockLedgerService.RESOURCE_PART, part.getId(), part.getPartCode(), part.getPartName(),
                    warehouseId, before, before + quantity, landedCost, order.getOperator(), order.getRemark(),
                    "PURCHASE_ORDER", order.getId(), null, receivedDate, StockBusinessType.PURCHASE_RECEIPT,
                    BigDecimal.ZERO, "PURCHASE-MOVEMENT:" + order.getId(), lot.getId()
            );
            part.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, part.getId()));
            part.setPurchasePrice(landedCost);
            part.setLandedUnitCost(landedCost);
            partInventoryRepository.save(part);
            order.setReceivedStockMovementId(movement.getId());
            order.setStockLotId(lot.getId());
        } else if (PurchaseOrder.RESOURCE_MACHINE.equals(order.getResourceType())) {
            if (quantity != 1) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "A serialized vehicle purchase order must receive exactly one vehicle");
            }
            MachineInventory machine = machineInventoryRepository.findByIdForUpdate(order.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Purchase vehicle not found"));
            if (Boolean.TRUE.equals(machine.getModelOnly())) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "Vehicle model template cannot be received as physical inventory");
            }
            int totalBefore = stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId());
            if (totalBefore > 0) {
                throw new BusinessException(ResultCode.CONFLICT, "A concrete vehicle cannot be received more than once");
            }
            int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId(), warehouseId);
            StockLot lot = stockLotService.createReceiptLot(
                    StockLedgerService.RESOURCE_MACHINE, machine.getId(), warehouseId, 1, landedCost,
                    MoneyValues.zeroIfNullOrNegative(order.getFreightAmount()), "PURCHASE_ORDER", order.getId(), null,
                    receivedDate, "PURCHASE-LOT:" + order.getId()
            );
            StockMovement movement = stockLedgerService.recordMovement(
                    "INBOUND", StockLedgerService.RESOURCE_MACHINE, machine.getId(), machine.getVehicleProductNumber(), machine.getName(),
                    warehouseId, before, before + 1, landedCost, order.getOperator(), order.getRemark(),
                    "PURCHASE_ORDER", order.getId(), null, receivedDate, StockBusinessType.PURCHASE_RECEIPT,
                    BigDecimal.ZERO, "PURCHASE-MOVEMENT:" + order.getId(), lot.getId()
            );
            machine.setWarehouseId(warehouseId);
            machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()));
            machine.setPurchasePrice(landedCost);
            machine.setLandedUnitCost(landedCost);
            machine.setStockStatus(com.example.forklift_erp.constant.MachineStockStatus.IN_STOCK.code());
            machine.setInboundDate(receivedDate.atStartOfDay());
            machineInventoryRepository.save(machine);
            order.setReceivedStockMovementId(movement.getId());
            order.setStockLotId(lot.getId());
        } else {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported purchase resource type: " + order.getResourceType());
        }
        order.setLandedUnitCost(landedCost);
        financialEventService.postPurchase(order, payable, "RECEIPT:" + order.getVersion());
        order.setFinancialPosted(true);
    }

    private void reverseReceiptIfPosted(PurchaseOrder order) {
        if (order.getReceivedStockMovementId() == null) {
            return;
        }
        if (order.getStockLotId() == null || !stockLotService.canReverseReceipt(order.getStockLotId())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Received inventory has been consumed and cannot be directly unreceived");
        }
        Long warehouseId = stockLedgerService.resolveWarehouseId(order.getWarehouseId());
        int quantity = order.getQuantity() == null ? 0 : order.getQuantity();
        BigDecimal unitCost = MoneyValues.zeroIfNullOrNegative(order.getLandedUnitCost());
        LocalDate date = order.getReceivedDate() == null ? LocalDate.now() : order.getReceivedDate();
        if (PurchaseOrder.RESOURCE_PART.equals(order.getResourceType())) {
            PartInventory part = partInventoryRepository.findByIdForUpdate(order.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Purchase part SKU not found"));
            int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_PART, part.getId(), warehouseId);
            if (before < quantity) {
                throw new BusinessException(ResultCode.CONFLICT, "Warehouse stock no longer covers the received quantity");
            }
            stockLedgerService.recordMovement(
                    "OUTBOUND", StockLedgerService.RESOURCE_PART, part.getId(), part.getPartCode(), part.getPartName(),
                    warehouseId, before, before - quantity, unitCost, order.getOperator(), "Reverse purchase receipt: " + order.getRemark(),
                    "PURCHASE_ORDER", order.getId(), null, date, StockBusinessType.PURCHASE_RECEIPT_REVERSAL,
                    BigDecimal.ZERO, "PURCHASE-REVERSAL:" + order.getId(), order.getStockLotId()
            );
            part.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, part.getId()));
            partInventoryRepository.save(part);
        } else if (PurchaseOrder.RESOURCE_MACHINE.equals(order.getResourceType())) {
            MachineInventory machine = machineInventoryRepository.findByIdForUpdate(order.getResourceId())
                    .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Purchase vehicle not found"));
            int before = stockLedgerService.availableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId(), warehouseId);
            if (before != 1) {
                throw new BusinessException(ResultCode.CONFLICT, "Serialized vehicle receipt is no longer reversible");
            }
            stockLedgerService.recordMovement(
                    "OUTBOUND", StockLedgerService.RESOURCE_MACHINE, machine.getId(), machine.getVehicleProductNumber(), machine.getName(),
                    warehouseId, before, 0, unitCost, order.getOperator(), "Reverse purchase receipt: " + order.getRemark(),
                    "PURCHASE_ORDER", order.getId(), null, date, StockBusinessType.PURCHASE_RECEIPT_REVERSAL,
                    BigDecimal.ZERO, "PURCHASE-REVERSAL:" + order.getId(), order.getStockLotId()
            );
            machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()));
            machine.setStockStatus(com.example.forklift_erp.constant.MachineStockStatus.PENDING_INBOUND.code());
            machineInventoryRepository.save(machine);
        }
        stockLotService.reverseReceiptLot(order.getStockLotId());
        financialEventService.reverseSourceEvents(
                FinancialEventService.SOURCE_PURCHASE_ORDER,
                order.getId(),
                List.of(com.example.forklift_erp.constant.FinancialEventType.ACCOUNTS_PAYABLE),
                LocalDate.now(),
                "Reverse purchase receipt",
                "PURCHASE-EVENT-REVERSAL:" + order.getId()
        );
        order.setReceivedStockMovementId(null);
        order.setStockLotId(null);
        order.setFinancialPosted(false);
    }

    private BigDecimal purchasePayable(PurchaseOrder order) {
        return MoneyValues.zeroIfNullOrNegative(order.getTotalAmount())
                .add(MoneyValues.zeroIfNullOrNegative(order.getFreightAmount()));
    }

    private BigDecimal landedUnitCost(PurchaseOrder order) {
        int quantity = order.getQuantity() == null || order.getQuantity() < 1 ? 1 : order.getQuantity();
        return purchasePayable(order).divide(BigDecimal.valueOf(quantity), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal totalAmount(Integer quantity, BigDecimal unitPrice, BigDecimal requestTotal) {
        if (requestTotal != null) {
            return MoneyValues.zeroIfNegative(requestTotal);
        }
        if (unitPrice == null) {
            return BigDecimal.ZERO;
        }
        return unitPrice.multiply(BigDecimal.valueOf(quantity == null ? 0 : quantity));
    }

    private Supplier resolveSupplier(PurchaseOrderDTO request, PurchaseOrder order, String resourceType) {
        if (request.getSupplierId() != null) {
            Supplier supplier = supplierRepository.findById(request.getSupplierId())
                    .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Supplier not found"));
            boolean preservingHistoricalSupplier = order.getId() != null
                    && java.util.Objects.equals(order.getSupplierId(), supplier.getId());
            if (!Boolean.TRUE.equals(supplier.getActive()) && !preservingHistoricalSupplier) {
                throw new BusinessException(ResultCode.CONFLICT, "Inactive supplier cannot be selected for a new purchase order");
            }
            return supplier;
        }
        throw new BusinessException(ResultCode.PARAM_ERROR, "Supplier is required for purchase orders");
    }

    private String supplierName(PurchaseOrderDTO request) {
        String name = blankToNull(request.getSupplierName());
        return name == null ? blankToNull(request.getSupplier()) : name;
    }

    private String normalizeResourceType(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return PurchaseOrder.RESOURCE_PART;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!PurchaseOrder.RESOURCE_PART.equals(normalized) && !PurchaseOrder.RESOURCE_MACHINE.equals(normalized)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported inbound resource type: " + normalized);
        }
        return normalized;
    }

    private String normalizeResourceTypeFilter(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        normalized = normalized.toUpperCase(Locale.ROOT);
        if (!PurchaseOrder.RESOURCE_PART.equals(normalized) && !PurchaseOrder.RESOURCE_MACHINE.equals(normalized)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Unsupported inbound resource type: " + normalized);
        }
        return normalized;
    }

    private String configItemLabel(ConfigItem item) {
        return List.of(item.getCategory(), item.getSubCategory(), item.getItemName()).stream()
                .map(this::blankToNull)
                .filter(part -> part != null)
                .reduce((left, right) -> left + " / " + right)
                .orElse(null);
    }

    private String nextPurchaseNo() {
        return BusinessNumberGenerator.next("PO", 6);
    }

    private String normalizeKeyword(String keyword) {
        return keyword == null || keyword.isBlank() ? null : keyword.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
