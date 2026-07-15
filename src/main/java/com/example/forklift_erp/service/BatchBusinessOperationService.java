package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.constant.RepairStatus;
import com.example.forklift_erp.dto.PurchaseOrderVO;
import com.example.forklift_erp.dto.RepairRecordVO;
import com.example.forklift_erp.dto.StocktakingRecordVO;
import com.example.forklift_erp.dto.VersionedBatchRequest;
import com.example.forklift_erp.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class BatchBusinessOperationService {
    private final RepairRecordService repairRecordService;
    private final PurchaseOrderService purchaseOrderService;
    private final StocktakingRecordService stocktakingRecordService;

    public BatchBusinessOperationService(
            RepairRecordService repairRecordService,
            PurchaseOrderService purchaseOrderService,
            StocktakingRecordService stocktakingRecordService
    ) {
        this.repairRecordService = repairRecordService;
        this.purchaseOrderService = purchaseOrderService;
        this.stocktakingRecordService = stocktakingRecordService;
    }

    @Transactional
    public List<RepairRecordVO> completeRepairs(VersionedBatchRequest request) {
        return orderedItems(request).stream()
                .map(item -> repairRecordService.updateStatus(
                        item.getId(), RepairStatus.COMPLETED.code(), item.getVersion()))
                .toList();
    }

    @Transactional
    public List<PurchaseOrderVO> receivePurchases(VersionedBatchRequest request) {
        return orderedItems(request).stream()
                .map(item -> purchaseOrderService.setReceived(item.getId(), true, item.getVersion()))
                .toList();
    }

    @Transactional
    public List<StocktakingRecordVO> completeStocktaking(VersionedBatchRequest request) {
        return orderedItems(request).stream()
                .map(item -> stocktakingRecordService.complete(item.getId(), item.getVersion()))
                .toList();
    }

    @Transactional
    public int deleteStocktakingDrafts(VersionedBatchRequest request) {
        List<VersionedBatchRequest.Item> items = orderedItems(request);
        items.forEach(item -> stocktakingRecordService.delete(item.getId(), item.getVersion()));
        return items.size();
    }

    private List<VersionedBatchRequest.Item> orderedItems(VersionedBatchRequest request) {
        if (request == null || request.getItems() == null || request.getItems().isEmpty()) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Batch items are required");
        }
        Set<Long> seen = new HashSet<>();
        for (VersionedBatchRequest.Item item : request.getItems()) {
            if (item == null || item.getId() == null || item.getVersion() == null) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Every batch item must include id and version");
            }
            if (!seen.add(item.getId())) {
                throw new BusinessException(ResultCode.PARAM_ERROR,
                        "Duplicate batch item: " + item.getId());
            }
        }
        return request.getItems().stream()
                .sorted(java.util.Comparator.comparing(VersionedBatchRequest.Item::getId))
                .toList();
    }
}
