package com.example.forklift_erp.service.impl;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.dto.OutboundInvoiceDownload;
import com.example.forklift_erp.dto.OutboundOrderVO;
import com.example.forklift_erp.entity.OutboundOrder;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.OutboundOrderRepository;
import com.example.forklift_erp.service.CollaborationService;
import com.example.forklift_erp.service.OperationAuditService;
import com.example.forklift_erp.service.ResourceAttachmentService;
import com.example.forklift_erp.service.ResourceVisibilityPolicy;
import com.example.forklift_erp.util.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Optional;

/**
 * Stores, retrieves and audits outbound invoices and contracts.
 */
@Service
public class OutboundDocumentService {
    private static final String SOURCE_TYPE = "OUTBOUND_ORDER";

    private final OutboundOrderRepository outboundOrderRepository;
    private final OperationAuditService operationAuditService;
    private final CollaborationService collaborationService;
    private final OutboundOrderFileStorage fileStorage;
    private final OutboundUploadReadinessPolicy uploadReadinessPolicy;
    private final ResourceAttachmentService resourceAttachmentService;
    private final ResourceVisibilityPolicy visibilityPolicy;

    public OutboundDocumentService(
            OutboundOrderRepository outboundOrderRepository,
            OperationAuditService operationAuditService,
            CollaborationService collaborationService,
            OutboundOrderFileStorage fileStorage,
            OutboundUploadReadinessPolicy uploadReadinessPolicy,
            ResourceAttachmentService resourceAttachmentService,
            ResourceVisibilityPolicy visibilityPolicy
    ) {
        this.outboundOrderRepository = outboundOrderRepository;
        this.operationAuditService = operationAuditService;
        this.collaborationService = collaborationService;
        this.fileStorage = fileStorage;
        this.uploadReadinessPolicy = uploadReadinessPolicy;
        this.resourceAttachmentService = resourceAttachmentService;
        this.visibilityPolicy = visibilityPolicy;
    }

    @Transactional
    public OutboundOrderVO uploadInvoice(Long id, MultipartFile file, Long version) {
        OutboundOrder order = writableOrder(id, version);
        if (!uploadReadinessPolicy.isInvoiceUploadReady(order)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Order is not ready for invoice upload");
        }
        StoredOutboundFile storedFile =
                fileStorage.storeInvoice(order.getId(), file, order.getInvoiceStoredFileName());
        order.setInvoiceStoredFileName(storedFile.storedFileName());
        order.setInvoiceOriginalName(storedFile.originalName());
        order.setInvoiceContentType(storedFile.contentType());
        order.setInvoiceFileSize(storedFile.fileSize());
        order.setInvoiceUploadedAt(storedFile.uploadedAt());
        collaborationService.stampWrite(order);

        OutboundOrder saved = outboundOrderRepository.saveAndFlush(order);
        resourceAttachmentService.recordLegacyOrderAttachment(saved, "INVOICE", storedFile);
        recordUpload(saved, "UPLOAD_INVOICE", "invoice", storedFile);
        return OutboundOrderVO.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public OutboundInvoiceDownload downloadInvoice(Long id) {
        return fileStorage.downloadInvoice(visibleOrder(id));
    }

    @Transactional
    public OutboundOrderVO uploadContract(Long id, MultipartFile file, Long version) {
        OutboundOrder order = writableOrder(id, version);
        if (!uploadReadinessPolicy.isContractUploadReady(order)) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "Order is not ready for contract upload");
        }
        StoredOutboundFile storedFile =
                fileStorage.storeContract(order.getId(), file, order.getContractStoredFileName());
        order.setContractStoredFileName(storedFile.storedFileName());
        order.setContractOriginalName(storedFile.originalName());
        order.setContractContentType(storedFile.contentType());
        order.setContractFileSize(storedFile.fileSize());
        order.setContractUploadedAt(storedFile.uploadedAt());
        collaborationService.stampWrite(order);

        OutboundOrder saved = outboundOrderRepository.saveAndFlush(order);
        resourceAttachmentService.recordLegacyOrderAttachment(saved, "CONTRACT", storedFile);
        recordUpload(saved, "UPLOAD_CONTRACT", "contract", storedFile);
        return OutboundOrderVO.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public OutboundInvoiceDownload downloadContract(Long id) {
        return fileStorage.downloadContract(visibleOrder(id));
    }

    private OutboundOrder writableOrder(Long id, Long version) {
        OutboundOrder order = outboundOrderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
        visibilityPolicy.ensureVisible(order.getIsLocked(), ResultCode.NOT_FOUND, "Outbound order not found");
        collaborationService.validateWrite(order, version);
        return order;
    }

    private OutboundOrder visibleOrder(Long id) {
        Optional<OutboundOrder> order = SecurityUtils.isAdminOrSuperAdmin()
                ? outboundOrderRepository.findById(id)
                : outboundOrderRepository.findByIdAndIsLockedFalse(id);
        return order.orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "Outbound order not found"));
    }

    private void recordUpload(
            OutboundOrder order,
            String action,
            String documentName,
            StoredOutboundFile storedFile
    ) {
        operationAuditService.record(
                "Outbound order",
                action,
                "OUTBOUND_ORDER",
                order.getId(),
                order.getOrderNo(),
                order.getCustomerName(),
                "Upload " + documentName + ": " + storedFile.originalName(),
                order.getOperator(),
                order.getOrderRemark(),
                SOURCE_TYPE,
                order.getId()
        );
    }
}
