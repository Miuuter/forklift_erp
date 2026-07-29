package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.ConfigReplaceLogRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderLineRepository;
import com.example.forklift_erp.repository.ModificationWorkOrderRepository;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.repository.PartInventoryRepository;
import com.example.forklift_erp.repository.PurchaseOrderRepository;
import com.example.forklift_erp.repository.RentalRecordRepository;
import com.example.forklift_erp.repository.RepairPartUsageRepository;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.ResourceAttachmentRepository;
import com.example.forklift_erp.repository.StockLotRepository;
import com.example.forklift_erp.repository.StockMovementLineRepository;
import com.example.forklift_erp.repository.StockOperationLogRepository;
import com.example.forklift_erp.repository.StocktakingRecordRepository;
import com.example.forklift_erp.service.StockLedgerService;
import org.springframework.stereotype.Component;

@Component
public class InventoryMasterDeletionGuard {
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final OutboundOrderRepository outboundOrderRepository;
    private final RentalRecordRepository rentalRecordRepository;
    private final RepairRecordRepository repairRecordRepository;
    private final ModificationWorkOrderRepository modificationWorkOrderRepository;
    private final ModificationWorkOrderLineRepository modificationWorkOrderLineRepository;
    private final RepairPartUsageRepository repairPartUsageRepository;
    private final ConfigReplaceLogRepository configReplaceLogRepository;
    private final PartInventoryRepository partInventoryRepository;
    private final StocktakingRecordRepository stocktakingRecordRepository;
    private final ResourceAttachmentRepository resourceAttachmentRepository;
    private final StockLotRepository stockLotRepository;
    private final StockMovementLineRepository stockMovementLineRepository;
    private final StockOperationLogRepository stockOperationLogRepository;

    public InventoryMasterDeletionGuard(
            PurchaseOrderRepository purchaseOrderRepository,
            OutboundOrderRepository outboundOrderRepository,
            RentalRecordRepository rentalRecordRepository,
            RepairRecordRepository repairRecordRepository,
            ModificationWorkOrderRepository modificationWorkOrderRepository,
            ModificationWorkOrderLineRepository modificationWorkOrderLineRepository,
            RepairPartUsageRepository repairPartUsageRepository,
            ConfigReplaceLogRepository configReplaceLogRepository,
            PartInventoryRepository partInventoryRepository,
            StocktakingRecordRepository stocktakingRecordRepository,
            ResourceAttachmentRepository resourceAttachmentRepository,
            StockLotRepository stockLotRepository,
            StockMovementLineRepository stockMovementLineRepository,
            StockOperationLogRepository stockOperationLogRepository
    ) {
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.outboundOrderRepository = outboundOrderRepository;
        this.rentalRecordRepository = rentalRecordRepository;
        this.repairRecordRepository = repairRecordRepository;
        this.modificationWorkOrderRepository = modificationWorkOrderRepository;
        this.modificationWorkOrderLineRepository = modificationWorkOrderLineRepository;
        this.repairPartUsageRepository = repairPartUsageRepository;
        this.configReplaceLogRepository = configReplaceLogRepository;
        this.partInventoryRepository = partInventoryRepository;
        this.stocktakingRecordRepository = stocktakingRecordRepository;
        this.resourceAttachmentRepository = resourceAttachmentRepository;
        this.stockLotRepository = stockLotRepository;
        this.stockMovementLineRepository = stockMovementLineRepository;
        this.stockOperationLogRepository = stockOperationLogRepository;
    }

    void ensureMachineDeletable(Long id) {
        conflict(purchaseOrderRepository.existsByResourceTypeAndResourceId(StockLedgerService.RESOURCE_MACHINE, id),
                "Vehicle has purchase records and cannot be deleted");
        conflict(outboundOrderRepository.existsByResourceTypeAndResourceId(StockLedgerService.RESOURCE_MACHINE, id),
                "Vehicle has outbound records and cannot be deleted");
        conflict(rentalRecordRepository.existsByMachineId(id),
                "Vehicle has rental records and cannot be deleted");
        conflict(repairRecordRepository.existsByMachineId(id),
                "Vehicle has repair records and cannot be deleted");
        conflict(modificationWorkOrderRepository.existsByMachineId(id),
                "Vehicle has modification records and cannot be deleted");
        conflict(configReplaceLogRepository.existsByMachineId(id),
                "Vehicle has configuration replacement records and cannot be deleted");
        conflict(partInventoryRepository.existsBySourceMachineId(id),
                "Vehicle is referenced as the source of a part and cannot be deleted");
        conflict(stocktakingRecordRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_MACHINE, id),
                "Vehicle has stocktaking records and cannot be deleted");
        conflict(resourceAttachmentRepository.existsByResourceTypeAndResourceIdAndDeletedFalse(
                        StockLedgerService.RESOURCE_MACHINE, id),
                "Vehicle has active attachments and cannot be deleted");
        conflict(stockLotRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_MACHINE, id),
                "Vehicle has FIFO history and cannot be deleted");
        conflict(stockMovementLineRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_MACHINE, id),
                "Vehicle has stock movement history and cannot be deleted");
        conflict(stockOperationLogRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_MACHINE, id),
                "Vehicle has stock operation history and cannot be deleted");
    }

    void ensurePartDeletable(Long id) {
        conflict(purchaseOrderRepository.existsByResourceTypeAndResourceId(StockLedgerService.RESOURCE_PART, id),
                "Part has purchase records and cannot be deleted");
        conflict(outboundOrderRepository.existsByResourceTypeAndResourceId(StockLedgerService.RESOURCE_PART, id),
                "Part has outbound records and cannot be deleted");
        conflict(modificationWorkOrderLineRepository.existsByNewPartId(id),
                "Part has modification usage records and cannot be deleted");
        conflict(repairPartUsageRepository.existsByPartId(id),
                "Part has repair usage records and cannot be deleted");
        conflict(configReplaceLogRepository.existsByNewPartId(id),
                "Part has configuration replacement records and cannot be deleted");
        conflict(stocktakingRecordRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_PART, id),
                "Part has stocktaking records and cannot be deleted");
        conflict(resourceAttachmentRepository.existsByResourceTypeAndResourceIdAndDeletedFalse(
                        StockLedgerService.RESOURCE_PART, id),
                "Part has active attachments and cannot be deleted");
        conflict(stockLotRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_PART, id),
                "Part has FIFO history and cannot be deleted");
        conflict(stockMovementLineRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_PART, id),
                "Part has stock movement history and cannot be deleted");
        conflict(stockOperationLogRepository.existsByResourceTypeAndResourceId(
                        StockLedgerService.RESOURCE_PART, id),
                "Part has stock operation history and cannot be deleted");
    }

    private void conflict(boolean condition, String message) {
        if (condition) {
            throw new BusinessException(ResultCode.CONFLICT, message);
        }
    }
}
