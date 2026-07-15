package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.MachineStockStatus;
import com.example.forklift_erp.constant.RentalStatus;
import com.example.forklift_erp.constant.StockBusinessType;
import com.example.forklift_erp.dto.OutboundInvoiceDownload;
import com.example.forklift_erp.dto.OutboundOrderUpdateDTO;
import com.example.forklift_erp.dto.OutboundOrderVO;
import com.example.forklift_erp.dto.PartOutboundOrderCreateDTO;
import com.example.forklift_erp.dto.VehicleOutboundOrderCreateDTO;
import com.example.forklift_erp.entity.Customer;
import com.example.forklift_erp.entity.MachineInventory;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.entity.PartInventory;
import com.example.forklift_erp.entity.StockMovement;
import com.example.forklift_erp.entity.StockMovementLine;
import com.example.forklift_erp.entity.StockOperationLog;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.CustomerRepository;
import com.example.forklift_erp.repository.MachineInventoryRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockMovementRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.FinancialEventService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.OutboundOrderService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.service.ResourceAttachmentService;
import com.example.forklift_erp.service.StockLedgerService;
import com.example.forklift_erp.service.StockLotService;
import com.example.forklift_erp.util.BusinessNumberGenerator;
import com.example.forklift_erp.util.InventoryQuantities;
import com.example.forklift_erp.util.ListPageSupport;
import com.example.forklift_erp.util.MoneyValues;
import com.example.forklift_erp.util.SearchKeywordSupport;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class OutboundOrderServiceImpl implements OutboundOrderService {

    private static final String SOURCE_TYPE = "OUTBOUND_ORDER";

    private final OutboundOrderRepository outboundOrderRepository;
    private final CustomerRepository customerRepository;
    private final MachineInventoryRepository machineRepository;
    private final PartInventoryRepository partRepository;
    private final RentalRecordRepository rentalRecordRepository;
    private final OperationAuditService operationAuditService;
    private final CollaborationService collaborationService;
    private final OutboundOrderFileStorage fileStorage;
    private final OutboundUploadReadinessPolicy uploadReadinessPolicy;
    private final OutboundResourceLockService resourceLockService;
    private final ResourceAttachmentService resourceAttachmentService;
    private final ResourceVisibilityPolicy visibilityPolicy;
    private final OutboundReceivablePolicy receivablePolicy;
    private final OutboundStockAccountingService stockAccountingService;
    private final StockLedgerService stockLedgerService;
    private final StockLotService stockLotService;
    private final FinancialEventService financialEventService;
    private final StockMovementRepository stockMovementRepository;
    private final StockMovementLineRepository stockMovementLineRepository;
    private final StockOperationLogRepository stockOperationLogRepository;

    public OutboundOrderServiceImpl(
            OutboundOrderRepository outboundOrderRepository,
            CustomerRepository customerRepository,
            MachineInventoryRepository machineRepository,
            PartInventoryRepository partRepository,
            RentalRecordRepository rentalRecordRepository,
            OperationAuditService operationAuditService,
            CollaborationService collaborationService,
            OutboundOrderFileStorage fileStorage,
            OutboundUploadReadinessPolicy uploadReadinessPolicy,
            OutboundResourceLockService resourceLockService,
            ResourceAttachmentService resourceAttachmentService,
            ResourceVisibilityPolicy visibilityPolicy,
            OutboundReceivablePolicy receivablePolicy,
            OutboundStockAccountingService stockAccountingService,
            StockLedgerService stockLedgerService,
            StockLotService stockLotService,
            FinancialEventService financialEventService,
            StockMovementRepository stockMovementRepository,
            StockMovementLineRepository stockMovementLineRepository,
            StockOperationLogRepository stockOperationLogRepository
    ) {
        this.outboundOrderRepository = outboundOrderRepository;
        this.customerRepository = customerRepository;
        this.machineRepository = machineRepository;
        this.partRepository = partRepository;
        this.rentalRecordRepository = rentalRecordRepository;
        this.operationAuditService = operationAuditService;
        this.collaborationService = collaborationService;
        this.fileStorage = fileStorage;
        this.uploadReadinessPolicy = uploadReadinessPolicy;
        this.resourceLockService = resourceLockService;
        this.resourceAttachmentService = resourceAttachmentService;
        this.visibilityPolicy = visibilityPolicy;
        this.receivablePolicy = receivablePolicy;
        this.stockAccountingService = stockAccountingService;
        this.stockLedgerService = stockLedgerService;
        this.stockLotService = stockLotService;
        this.financialEventService = financialEventService;
        this.stockMovementRepository = stockMovementRepository;
        this.stockMovementLineRepository = stockMovementLineRepository;
        this.stockOperationLogRepository = stockOperationLogRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboundOrderVO> findAll() {
        List<OutboundOrder> orders = SecurityUtils.isAdminOrSuperAdmin()
                ? outboundOrderRepository.findAllByOrderByCreatedAtDesc()
                : outboundOrderRepository.findAllByIsLockedFalseOrderByCreatedAtDesc();
        return orders.stream()
                .map(OutboundOrderVO::fromEntity)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<OutboundOrderVO> findPage(String keyword, String stage, Integer page, Integer size) {
        int normalizedPage = ListPageSupport.page(page);
        int normalizedSize = ListPageSupport.size(size);
        Page<OutboundOrder> result = outboundOrderRepository.searchPage(
                SearchKeywordSupport.likePrefix(keyword),
                SearchKeywordSupport.fullTextBoolean(keyword),
                SecurityUtils.isAdminOrSuperAdmin(),
                normalizeStage(stage),
                ListPageSupport.pageRequest(page, size)
        );
        return PageResult.of(
                result.getContent().stream().map(OutboundOrderVO::fromEntity).toList(),
                normalizedPage,
                normalizedSize,
                result.getTotalElements()
        );
    }

    private String normalizeStage(String stage) {
        if (stage == null || stage.isBlank()) {
            return null;
        }
        return switch (stage.trim()) {
            case "payment", "overdue", "salesReport", "invoiceApplication", "invoiceFile", "contractFile", "closed" -> stage.trim();
            default -> null;
        };
    }

    @Override
    @Transactional(readOnly = true)
    public OutboundOrderVO findById(Long id) {
        return visibleOrderById(id)
                .map(OutboundOrderVO::fromEntity)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
    }

    @Override
    @Transactional
    public OutboundOrderVO createVehicleOutbound(VehicleOutboundOrderCreateDTO request) {
        MachineInventory machine = machineRepository.findByIdForUpdate(request.getMachineId())
                .orElseThrow(() -> new BusinessException(ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found"));
        ensureResourceVisible(Boolean.TRUE.equals(machine.getIsLocked()), ResultCode.VEHICLE_NOT_FOUND, "Vehicle not found");
        if (Boolean.TRUE.equals(machine.getModelOnly())) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Model-only vehicle cannot be outbounded");
        }
        if (MachineStockStatus.RENTED.code().equals(machine.getStockStatus())
                || rentalRecordRepository.existsByMachineIdAndStatus(machine.getId(), RentalStatus.ACTIVE.code())) {
            throw new BusinessException(ResultCode.CONFLICT, "车辆正在租赁中，不能创建销售出库订单");
        }
        if (!MachineStockStatus.canSell(machine.getStockStatus())) {
            throw new BusinessException(ResultCode.CONFLICT,
                    "Vehicle status does not allow sales outbound: " + machine.getStockStatus());
        }
        collaborationService.validateWrite(machine, request.getMachineVersion());
        Long sourceWarehouseId = stockLedgerService.resolveWarehouseId(request.getWarehouseId());
        if (machine.getWarehouseId() != null && !sourceWarehouseId.equals(machine.getWarehouseId())) {
            throw new BusinessException(ResultCode.CONFLICT, "Serialized vehicle is not in the selected warehouse");
        }
        int warehouseAvailable = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_MACHINE, machine.getId(), sourceWarehouseId);
        InventoryQuantities.QuantityChange stockChange = InventoryQuantities.outbound(
                warehouseAvailable,
                1,
                "Outbound quantity must be greater than 0",
                before -> "Vehicle stock is insufficient"
        );

        Customer customer = findCustomer(request.getCustomerId());
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo(nextOrderNo());
        order.setResourceType(OutboundOrder.RESOURCE_MACHINE);
        order.setResourceId(machine.getId());
        order.setSourceWarehouseId(sourceWarehouseId);
        order.setResourceCode(machine.getVehicleProductNumber());
        order.setResourceName(machine.getName());
        order.setSpecificationModel(machine.getSpecificationModel());
        order.setQuantity(1);
        order.setUnit("\u53f0");
        copyCustomer(order, customer);
        BigDecimal unitSalePrice = resolveUnitSalePrice(
                request.getUnitSalePrice(), request.getSettlementPrice(), request.getSalePrice(), machine.getSalePrice());
        BigDecimal lineAmount = resolveLineAmount(request.getLineAmount(), request.getReceivableAmount(), unitSalePrice, 1);
        order.setSettlementPrice(unitSalePrice);
        order.setUnitSalePrice(unitSalePrice);
        order.setLineAmount(lineAmount);
        order.setSalesDate(request.getSalesDate());
        order.setSalePrice(MoneyValues.firstNonNegativeOrNull(request.getSalePrice(), machine.getSalePrice()));
        order.setReceivableAmount(lineAmount);
        order.setReceivedAmount(BigDecimal.ZERO);
        order.setPaymentDueDate(request.getPaymentDueDate());
        order.setLastPaymentDate(request.getLastPaymentDate());
        if (request.getPaymentSettled() != null) {
            order.setPaymentSettled(request.getPaymentSettled());
        }
        order.setPaymentRemark(blankToNull(request.getPaymentRemark()));
        if (request.getSalesReported() != null) {
            order.setSalesReported(request.getSalesReported());
        }
        if (request.getInvoiceApplied() != null) {
            order.setInvoiceApplied(request.getInvoiceApplied());
        }
        order.setSalesReportDate(request.getSalesReportDate());
        order.setInvoiceApplicationDate(request.getInvoiceApplicationDate());
        order.setInvoiceStatus(blankToNull(request.getInvoiceStatus()));
        order.setInvoiceIssuedDate(request.getInvoiceIssuedDate());
        order.setRegistrationStatus(blankToNull(request.getRegistrationStatus()));
        order.setContractType(blankToNull(request.getContractType()));
        order.setOperator(blankToNull(request.getOperator()));
        order.setOrderRemark(blankToNull(request.getOrderRemark()));
        receivablePolicy.apply(order, request.getPaymentSettled());
        collaborationService.stampWrite(order);
        OutboundOrder savedOrder = outboundOrderRepository.save(order);

        StockLotService.ConsumptionResult fifo = stockLotService.consumeFifo(
                StockLedgerService.RESOURCE_MACHINE,
                machine.getId(),
                sourceWarehouseId,
                1,
                stockAccountingService.machineUnitCost(machine),
                warehouseAvailable,
                SOURCE_TYPE,
                savedOrder.getId(),
                null,
                salesBusinessDate(savedOrder),
                "SALE:" + savedOrder.getId() + ":FIFO"
        );
        machine.setInventoryCount(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_MACHINE, machine.getId()) - 1);
        machine.setStockStatus(machine.getInventoryCount() > 0 ? MachineStockStatus.IN_STOCK.code() : MachineStockStatus.OUTBOUND.code());
        machine.setSalePrice(MoneyValues.firstNonNegativeOrNull(request.getSalePrice(), machine.getSalePrice()));
        machine.setSalesDate(toMachineSalesDate(request.getSalesDate()));
        machine.setDestination1(customer.getCompanyName());
        machine.setIsSalesReported(yesNo(order.getSalesReported()));
        machine.setSalesReportDate(order.getSalesReportDate());
        machine.setIsInvoiceApplied(yesNo(order.getInvoiceApplied()));
        collaborationService.stampWrite(machine);
        MachineInventory savedMachine = machineRepository.save(machine);

        StockOperationLog stockLog = stockAccountingService.recordMachineOutbound(
                savedMachine,
                savedOrder,
                stockChange,
                fifo.unitCost(),
                sourceWarehouseId,
                request.getOperator(),
                joinRemark("Vehicle outbound order " + savedOrder.getOrderNo(), request.getOrderRemark())
        );
        savedOrder.setStockOperationLogId(stockLog.getId());
        finalizeOutboundMovement(savedOrder, fifo, salesBusinessDate(savedOrder));
        financialEventService.postSales(savedOrder, fifo.totalCost(), "CREATE:" + savedOrder.getVersion());
        syncReceipt(savedOrder, receiptTarget(request.getReceivedAmount(), request.getPaymentSettled(), lineAmount),
                request.getLastPaymentDate(), request.getPaymentRemark(), "CREATE");
        receivablePolicy.apply(savedOrder, request.getPaymentSettled());
        OutboundOrder result = outboundOrderRepository.saveAndFlush(savedOrder);

        operationAuditService.record("Outbound order", "CREATE", "OUTBOUND_ORDER", result.getId(),
                result.getOrderNo(), result.getCustomerName(),
                "Create vehicle outbound order " + result.getResourceCode(), result.getOperator(), result.getOrderRemark(),
                SOURCE_TYPE, result.getId());
        return OutboundOrderVO.fromEntity(result);
    }

    @Override
    @Transactional
    public OutboundOrderVO createPartOutbound(PartOutboundOrderCreateDTO request) {
        PartInventory part = partRepository.findByPartCodeForUpdate(request.getPartCode())
                .orElseThrow(() -> new BusinessException(ResultCode.PART_NOT_FOUND, "Part not found"));
        ensureResourceVisible(Boolean.TRUE.equals(part.getIsLocked()), ResultCode.PART_NOT_FOUND, "Part not found");
        collaborationService.validateWrite(part, request.getPartVersion());
        Long sourceWarehouseId = stockLedgerService.resolveWarehouseId(request.getWarehouseId());
        int warehouseAvailable = stockLedgerService.availableQuantity(
                StockLedgerService.RESOURCE_PART, part.getId(), sourceWarehouseId);
        InventoryQuantities.QuantityChange stockChange = InventoryQuantities.outbound(
                warehouseAvailable,
                request.getQuantity(),
                "Outbound quantity must be greater than 0",
                "Part stock is insufficient: "
        );
        int quantity = stockChange.quantity();

        Customer customer = findCustomer(request.getCustomerId());
        OutboundOrder order = new OutboundOrder();
        order.setOrderNo(nextOrderNo());
        order.setResourceType(OutboundOrder.RESOURCE_PART);
        order.setResourceId(part.getId());
        order.setSourceWarehouseId(sourceWarehouseId);
        order.setResourceCode(part.getPartCode());
        order.setResourceName(part.getPartName());
        order.setSpecificationModel(part.getSpecification());
        order.setQuantity(quantity);
        order.setUnit(part.getUnit());
        copyCustomer(order, customer);
        BigDecimal unitSalePrice = resolveUnitSalePrice(
                request.getUnitSalePrice(), request.getSettlementPrice(), part.getSalePrice(), part.getSettlementPrice());
        BigDecimal lineAmount = resolveLineAmount(request.getLineAmount(), request.getReceivableAmount(), unitSalePrice, quantity);
        order.setSettlementPrice(unitSalePrice);
        order.setUnitSalePrice(unitSalePrice);
        order.setLineAmount(lineAmount);
        order.setSalesDate(request.getSalesDate());
        order.setReceivableAmount(lineAmount);
        order.setReceivedAmount(BigDecimal.ZERO);
        order.setPaymentDueDate(request.getPaymentDueDate());
        order.setLastPaymentDate(request.getLastPaymentDate());
        if (request.getPaymentSettled() != null) {
            order.setPaymentSettled(request.getPaymentSettled());
        }
        order.setPaymentRemark(blankToNull(request.getPaymentRemark()));
        order.setOperator(blankToNull(request.getOperator()));
        order.setOrderRemark(blankToNull(request.getOrderRemark()));
        receivablePolicy.apply(order, request.getPaymentSettled());
        collaborationService.stampWrite(order);
        OutboundOrder savedOrder = outboundOrderRepository.save(order);

        StockLotService.ConsumptionResult fifo = stockLotService.consumeFifo(
                StockLedgerService.RESOURCE_PART,
                part.getId(),
                sourceWarehouseId,
                quantity,
                stockAccountingService.partUnitCost(part),
                warehouseAvailable,
                SOURCE_TYPE,
                savedOrder.getId(),
                null,
                salesBusinessDate(savedOrder),
                "SALE:" + savedOrder.getId() + ":FIFO"
        );
        part.setQuantity(stockLedgerService.totalAvailableQuantity(StockLedgerService.RESOURCE_PART, part.getId()) - quantity);
        part.setIsSalesReported("\u5426");
        collaborationService.stampWrite(part);
        PartInventory savedPart = partRepository.save(part);

        StockOperationLog stockLog = stockAccountingService.recordPartOutbound(
                savedPart,
                savedOrder,
                stockChange,
                fifo.unitCost(),
                sourceWarehouseId,
                request.getOperator(),
                joinRemark("Part outbound order " + savedOrder.getOrderNo(), request.getOrderRemark())
        );
        savedOrder.setStockOperationLogId(stockLog.getId());
        finalizeOutboundMovement(savedOrder, fifo, salesBusinessDate(savedOrder));
        financialEventService.postSales(savedOrder, fifo.totalCost(), "CREATE:" + savedOrder.getVersion());
        syncReceipt(savedOrder, receiptTarget(request.getReceivedAmount(), request.getPaymentSettled(), lineAmount),
                request.getLastPaymentDate(), request.getPaymentRemark(), "CREATE");
        receivablePolicy.apply(savedOrder, request.getPaymentSettled());
        OutboundOrder result = outboundOrderRepository.saveAndFlush(savedOrder);

        operationAuditService.record("Outbound order", "CREATE", "OUTBOUND_ORDER", result.getId(),
                result.getOrderNo(), result.getCustomerName(),
                "Create part outbound order " + result.getResourceCode() + " x" + quantity,
                result.getOperator(), result.getOrderRemark(), SOURCE_TYPE, result.getId());
        return OutboundOrderVO.fromEntity(result);
    }

    @Override
    @Transactional
    public OutboundOrderVO update(Long id, OutboundOrderUpdateDTO request) {
        OutboundOrder order = outboundOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
        ensureOrderVisible(order);
        collaborationService.validateWrite(order, request.getVersion());
        boolean unitSalePriceChanged = request.getUnitSalePrice() != null || request.getSettlementPrice() != null;
        if (request.getSettlementPrice() != null) {
            order.setSettlementPrice(MoneyValues.firstNonNegativeOrNull(request.getSettlementPrice(), BigDecimal.ZERO));
        }
        if (request.getUnitSalePrice() != null) {
            order.setUnitSalePrice(MoneyValues.zeroIfNullOrNegative(request.getUnitSalePrice()));
            order.setSettlementPrice(order.getUnitSalePrice());
        } else if (request.getSettlementPrice() != null) {
            order.setUnitSalePrice(order.getSettlementPrice());
        }
        if (request.getSalesDate() != null) {
            order.setSalesDate(request.getSalesDate());
        }
        if (request.getSalePrice() != null) {
            order.setSalePrice(MoneyValues.firstNonNegativeOrNull(request.getSalePrice(), BigDecimal.ZERO));
        }
        if (request.getLineAmount() != null) {
            order.setLineAmount(MoneyValues.zeroIfNullOrNegative(request.getLineAmount()));
        } else if (request.getReceivableAmount() != null) {
            order.setLineAmount(MoneyValues.zeroIfNullOrNegative(request.getReceivableAmount()));
        } else if (unitSalePriceChanged || order.getLineAmount() == null) {
            order.setLineAmount(resolveLineAmount(null, null,
                    MoneyValues.firstNonNegativeOrNull(order.getUnitSalePrice(), order.getSettlementPrice(), order.getSalePrice()),
                    order.getQuantity()));
        }
        order.setReceivableAmount(MoneyValues.zeroIfNullOrNegative(order.getLineAmount()));
        if (request.getPaymentDueDate() != null) {
            order.setPaymentDueDate(request.getPaymentDueDate());
        }
        if (request.getLastPaymentDate() != null) {
            order.setLastPaymentDate(request.getLastPaymentDate());
        }
        if (request.getPaymentSettled() != null) {
            order.setPaymentSettled(request.getPaymentSettled());
        }
        if (request.getPaymentRemark() != null) {
            order.setPaymentRemark(blankToNull(request.getPaymentRemark()));
        }
        if (request.getSalesReported() != null) {
            order.setSalesReported(request.getSalesReported());
        }
        if (request.getInvoiceApplied() != null) {
            order.setInvoiceApplied(request.getInvoiceApplied());
        }
        if (request.getSalesReportDate() != null) {
            order.setSalesReportDate(request.getSalesReportDate());
        }
        if (request.getInvoiceApplicationDate() != null) {
            order.setInvoiceApplicationDate(request.getInvoiceApplicationDate());
        }
        if (request.getInvoiceStatus() != null) {
            order.setInvoiceStatus(blankToNull(request.getInvoiceStatus()));
        }
        if (request.getInvoiceIssuedDate() != null) {
            order.setInvoiceIssuedDate(request.getInvoiceIssuedDate());
        }
        if (request.getRegistrationStatus() != null) {
            order.setRegistrationStatus(blankToNull(request.getRegistrationStatus()));
        }
        if (request.getContractType() != null) {
            order.setContractType(blankToNull(request.getContractType()));
        }
        if (request.getOrderRemark() != null) {
            order.setOrderRemark(blankToNull(request.getOrderRemark()));
        }
        if (request.getOperator() != null && !request.getOperator().isBlank()) {
            order.setOperator(request.getOperator().trim());
        }
        receivablePolicy.apply(order, request.getPaymentSettled());
        collaborationService.stampWrite(order);
        OutboundOrder saved = outboundOrderRepository.saveAndFlush(order);
        updateOutboundMovementPricing(saved);
        financialEventService.replaceSalesPosting(saved, outboundCost(saved), Boolean.TRUE.equals(saved.getIsLocked()));
        if (request.getReceivedAmount() != null || Boolean.TRUE.equals(request.getPaymentSettled())) {
            syncReceipt(saved, receiptTarget(request.getReceivedAmount(), request.getPaymentSettled(), saved.getReceivableAmount()),
                    request.getLastPaymentDate(), request.getPaymentRemark(), "REVISION:" + saved.getVersion());
            receivablePolicy.apply(saved, request.getPaymentSettled());
            saved = outboundOrderRepository.saveAndFlush(saved);
        }
        syncLegacyOutboundFlags(saved);
        operationAuditService.record("Outbound order", "UPDATE", "OUTBOUND_ORDER", saved.getId(),
                saved.getOrderNo(), saved.getCustomerName(), "Update outbound order status",
                saved.getOperator(), saved.getOrderRemark(), SOURCE_TYPE, saved.getId());
        return OutboundOrderVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public OutboundOrderVO setLocked(Long id, boolean locked, Long version) {
        if (!SecurityUtils.isAdminOrSuperAdmin()) {
            throw new BusinessException(ResultCode.FORBIDDEN, "Only admins can lock outbound orders");
        }
        OutboundOrder order = outboundOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
        collaborationService.validateWrite(order, version);
        if (locked) {
            order.setIsLocked(true);
            resourceLockService.lockRelatedResource(order);
        } else {
            resourceLockService.releaseRelatedResource(order);
            order.setIsLocked(false);
        }
        collaborationService.stampWrite(order);
        OutboundOrder saved = outboundOrderRepository.saveAndFlush(order);
        operationAuditService.record("Outbound order", locked ? "LOCK" : "UNLOCK", "OUTBOUND_ORDER", saved.getId(),
                saved.getOrderNo(), saved.getCustomerName(),
                locked ? "Lock outbound order and related resource" : "Unlock outbound order and related resource",
                saved.getOperator(), saved.getOrderRemark(), SOURCE_TYPE, saved.getId());
        return OutboundOrderVO.fromEntity(saved);
    }

    @Override
    @Transactional
    public OutboundOrderVO uploadInvoice(Long id, MultipartFile file, Long version) {
        OutboundOrder order = outboundOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
        ensureOrderVisible(order);
        collaborationService.validateWrite(order, version);
        if (!uploadReadinessPolicy.isInvoiceUploadReady(order)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Order is not ready for invoice upload");
        }
        StoredOutboundFile storedFile = fileStorage.storeInvoice(order.getId(), file, order.getInvoiceStoredFileName());

        order.setInvoiceStoredFileName(storedFile.storedFileName());
        order.setInvoiceOriginalName(storedFile.originalName());
        order.setInvoiceContentType(storedFile.contentType());
        order.setInvoiceFileSize(storedFile.fileSize());
        order.setInvoiceUploadedAt(storedFile.uploadedAt());
        collaborationService.stampWrite(order);

        OutboundOrder saved = outboundOrderRepository.saveAndFlush(order);
        resourceAttachmentService.recordLegacyOrderAttachment(saved, "INVOICE", storedFile);
        operationAuditService.record("Outbound order", "UPLOAD_INVOICE", "OUTBOUND_ORDER", saved.getId(),
                saved.getOrderNo(), saved.getCustomerName(), "Upload invoice: " + storedFile.originalName(),
                saved.getOperator(), saved.getOrderRemark(), SOURCE_TYPE, saved.getId());
        return OutboundOrderVO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OutboundInvoiceDownload downloadInvoice(Long id) {
        OutboundOrder order = visibleOrderById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
        return fileStorage.downloadInvoice(order);
    }

    @Override
    @Transactional
    public OutboundOrderVO uploadContract(Long id, MultipartFile file, Long version) {
        OutboundOrder order = outboundOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
        ensureOrderVisible(order);
        collaborationService.validateWrite(order, version);
        if (!uploadReadinessPolicy.isContractUploadReady(order)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Order is not ready for contract upload");
        }

        StoredOutboundFile storedFile = fileStorage.storeContract(order.getId(), file, order.getContractStoredFileName());

        order.setContractStoredFileName(storedFile.storedFileName());
        order.setContractOriginalName(storedFile.originalName());
        order.setContractContentType(storedFile.contentType());
        order.setContractFileSize(storedFile.fileSize());
        order.setContractUploadedAt(storedFile.uploadedAt());
        collaborationService.stampWrite(order);

        OutboundOrder saved = outboundOrderRepository.saveAndFlush(order);
        resourceAttachmentService.recordLegacyOrderAttachment(saved, "CONTRACT", storedFile);
        operationAuditService.record("Outbound order", "UPLOAD_CONTRACT", "OUTBOUND_ORDER", saved.getId(),
                saved.getOrderNo(), saved.getCustomerName(), "Upload contract: " + storedFile.originalName(),
                saved.getOperator(), saved.getOrderRemark(), SOURCE_TYPE, saved.getId());
        return OutboundOrderVO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OutboundInvoiceDownload downloadContract(Long id) {
        OutboundOrder order = visibleOrderById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
        return fileStorage.downloadContract(order);
    }

    private Optional<OutboundOrder> visibleOrderById(Long id) {
        if (SecurityUtils.isAdminOrSuperAdmin()) {
            return outboundOrderRepository.findById(id);
        }
        return outboundOrderRepository.findByIdAndIsLockedFalse(id);
    }

    private void ensureOrderVisible(OutboundOrder order) {
        visibilityPolicy.ensureVisible(order.getIsLocked(), ResultCode.NOT_FOUND, "Outbound order not found");
    }

    private void ensureResourceVisible(boolean locked, ResultCode resultCode, String message) {
        visibilityPolicy.ensureVisible(locked, resultCode, message);
    }

    private Customer findCustomer(Long customerId) {
        return customerRepository.findByIdForUpdate(customerId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Customer not found"));
    }

    private void copyCustomer(OutboundOrder order, Customer customer) {
        order.setCustomerId(customer.getId());
        order.setCustomerName(customer.getCompanyName());
        order.setCustomerAddress(customer.getAddress());
        order.setContactName(customer.getContactName());
        order.setContactPhone(customer.getContactPhone());
        order.setTaxOrIdNumber(customer.getTaxOrIdNumber());
    }

    private void syncLegacyOutboundFlags(OutboundOrder order) {
        if (OutboundOrder.RESOURCE_MACHINE.equals(order.getResourceType())) {
            machineRepository.findByIdForUpdate(order.getResourceId()).ifPresent(machine -> {
                machine.setSalePrice(MoneyValues.firstNonNegativeOrNull(order.getSalePrice(), machine.getSalePrice()));
                machine.setSalesDate(toMachineSalesDate(order.getSalesDate()));
                machine.setIsSalesReported(yesNo(order.getSalesReported()));
                machine.setSalesReportDate(order.getSalesReportDate());
                machine.setIsInvoiceApplied(yesNo(order.getInvoiceApplied()));
                collaborationService.stampWrite(machine);
                machineRepository.save(machine);
            });
            return;
        }
        if (OutboundOrder.RESOURCE_PART.equals(order.getResourceType())) {
            partRepository.findByIdForUpdate(order.getResourceId()).ifPresent(part -> {
                part.setIsSalesReported(yesNo(order.getSalesReported()));
                part.setSalesReportDate(order.getSalesReportDate());
                collaborationService.stampWrite(part);
                partRepository.save(part);
            });
        }
    }

    private BigDecimal resolveUnitSalePrice(BigDecimal... values) {
        return MoneyValues.firstNonNegativeOrNull(values) == null
                ? BigDecimal.ZERO
                : MoneyValues.firstNonNegativeOrNull(values);
    }

    private BigDecimal resolveLineAmount(
            BigDecimal requestedLineAmount,
            BigDecimal requestedReceivableAmount,
            BigDecimal unitSalePrice,
            Integer quantity
    ) {
        BigDecimal explicit = MoneyValues.firstNonNegativeOrNull(requestedLineAmount, requestedReceivableAmount);
        if (explicit != null) {
            return explicit;
        }
        int safeQuantity = quantity == null || quantity < 1 ? 1 : quantity;
        return MoneyValues.zeroIfNullOrNegative(unitSalePrice).multiply(BigDecimal.valueOf(safeQuantity));
    }

    private LocalDate salesBusinessDate(OutboundOrder order) {
        return order.getSalesDate() == null ? LocalDate.now() : order.getSalesDate();
    }

    private BigDecimal receiptTarget(BigDecimal requestedReceivedAmount, Boolean paymentSettled, BigDecimal receivableAmount) {
        if (Boolean.TRUE.equals(paymentSettled)) {
            return MoneyValues.zeroIfNullOrNegative(receivableAmount);
        }
        return MoneyValues.zeroIfNullOrNegative(requestedReceivedAmount);
    }

    private void syncReceipt(
            OutboundOrder order,
            BigDecimal targetAmount,
            LocalDate paymentDate,
            String paymentRemark,
            String revision
    ) {
        financialEventService.syncReceiptToTarget(
                FinancialEventService.SOURCE_OUTBOUND_ORDER,
                order.getId(),
                targetAmount,
                paymentDate == null ? salesBusinessDate(order) : paymentDate,
                null,
                null,
                paymentRemark,
                "SALE-RECEIPT:" + order.getId() + ":" + revision
        );
        order.setReceivedAmount(financialEventService.receiptTotal(
                FinancialEventService.SOURCE_OUTBOUND_ORDER, order.getId()));
    }

    private void finalizeOutboundMovement(
            OutboundOrder order,
            StockLotService.ConsumptionResult fifo,
            LocalDate businessDate
    ) {
        List<StockMovement> movements = stockMovementRepository.findBySourceTypeAndSourceId(SOURCE_TYPE, order.getId());
        Long firstLotId = fifo.consumptions().isEmpty() ? null : fifo.consumptions().get(0).getStockLotId();
        for (StockMovement movement : movements) {
            movement.setBusinessDate(businessDate);
            movement.setBusinessType(StockBusinessType.SALE_OUTBOUND);
            stockMovementRepository.save(movement);
            List<StockMovementLine> lines = stockMovementLineRepository.findByMovementIdOrderByIdAsc(movement.getId());
            for (StockMovementLine line : lines) {
                line.setUnitCost(fifo.unitCost());
                line.setCostAmount(fifo.totalCost());
                line.setUnitRevenue(resolveUnitSalePrice(order.getUnitSalePrice(), order.getSettlementPrice(), order.getSalePrice()));
                line.setLineAmount(MoneyValues.zeroIfNullOrNegative(order.getLineAmount()));
                line.setStockLotId(firstLotId);
                stockMovementLineRepository.save(line);
            }
        }
        if (order.getStockOperationLogId() != null) {
            stockOperationLogRepository.findById(order.getStockOperationLogId()).ifPresent(log -> {
                log.setUnitCost(fifo.unitCost());
                log.setUnitRevenue(resolveUnitSalePrice(order.getUnitSalePrice(), order.getSettlementPrice(), order.getSalePrice()));
                stockOperationLogRepository.save(log);
            });
        }
    }

    private void updateOutboundMovementPricing(OutboundOrder order) {
        if (Boolean.TRUE.equals(order.getIsLocked())) {
            return;
        }
        StockLotService.ConsumptionResult snapshot = new StockLotService.ConsumptionResult(
                outboundCost(order),
                unitCostForOrder(order),
                List.of()
        );
        finalizeOutboundMovement(order, snapshot, salesBusinessDate(order));
    }

    private BigDecimal outboundCost(OutboundOrder order) {
        if (order.getStockOperationLogId() == null) {
            return BigDecimal.ZERO;
        }
        return stockOperationLogRepository.findById(order.getStockOperationLogId())
                .map(log -> MoneyValues.zeroIfNullOrNegative(log.getUnitCost())
                        .multiply(BigDecimal.valueOf(order.getQuantity() == null ? 1 : order.getQuantity())))
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal unitCostForOrder(OutboundOrder order) {
        int quantity = order.getQuantity() == null || order.getQuantity() < 1 ? 1 : order.getQuantity();
        return outboundCost(order).divide(BigDecimal.valueOf(quantity), 2, java.math.RoundingMode.HALF_UP);
    }

    private String nextOrderNo() {
        return BusinessNumberGenerator.next("OO", 6);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String joinRemark(String... values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append("; ");
            }
            builder.append(value.trim());
        }
        return builder.isEmpty() ? null : builder.toString();
    }

    private String toMachineSalesDate(LocalDate salesDate) {
        return salesDate == null ? null : salesDate.toString();
    }

    private String yesNo(Boolean value) {
        return Boolean.TRUE.equals(value) ? "\u662f" : "\u5426";
    }
}
