package com.example.forklift_erp.repository;

import com.example.forklift_erp.entity.OutboundOrder;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Repository
public interface OutboundOrderRepository extends JpaRepository<OutboundOrder, Long> {
    List<OutboundOrder> findAllByOrderByCreatedAtDesc();

    List<OutboundOrder> findAllByIsLockedFalseOrderByCreatedAtDesc();

    @Query(value = """
            select o.*
            from outbound_order o
            where (:includeLocked = true or coalesce(o.is_locked, 0) = 0)
              and (
                :stage is null
                or (:stage = 'payment'
                    and (o.payment_due_date is null or o.payment_due_date >= current_date)
                    and coalesce(o.receivable_amount, o.settlement_price, 0) - coalesce(o.received_amount, 0) > 0)
                or (:stage = 'overdue'
                    and o.payment_due_date is not null and o.payment_due_date < current_date
                    and coalesce(o.receivable_amount, o.settlement_price, 0) - coalesce(o.received_amount, 0) > 0)
                or (:stage = 'salesReport' and coalesce(o.payment_settled, 0) = 1 and coalesce(o.sales_reported, 0) = 0)
                or (:stage = 'invoiceApplication' and coalesce(o.payment_settled, 0) = 1 and coalesce(o.sales_reported, 0) = 1 and coalesce(o.invoice_applied, 0) = 0)
                or (:stage = 'invoiceFile' and (
                      coalesce(o.invoice_applied, 0) = 1 or o.invoice_issued_date is not null
                      or lower(coalesce(o.invoice_status, '')) like '%issued%'
                      or lower(coalesce(o.invoice_status, '')) like '%invoiced%'
                      or coalesce(o.invoice_status, '') like '%开票完成%'
                      or coalesce(o.invoice_status, '') like '%完成开票%'
                      or coalesce(o.invoice_status, '') like '%已出票%'
                    ) and coalesce(o.invoice_stored_file_name, '') = '')
                or (:stage = 'contractFile' and o.contract_type is not null and trim(o.contract_type) <> ''
                    and lower(trim(o.contract_type)) not like 'no%' and lower(trim(o.contract_type)) not like 'none%'
                    and lower(trim(o.contract_type)) not like 'false%' and trim(o.contract_type) not like '否%'
                    and trim(o.contract_type) not like '无%' and trim(o.contract_type) not like '没%'
                    and trim(o.contract_type) not like '不%' and trim(o.contract_type) not like '%无合同%'
                    and coalesce(o.contract_stored_file_name, '') = '')
                or (:stage = 'closed' and coalesce(o.payment_settled, 0) = 1 and coalesce(o.sales_reported, 0) = 1
                    and coalesce(o.invoice_applied, 0) = 1 and coalesce(o.invoice_stored_file_name, '') <> '')
              )
              and (:keywordPrefix is null
                or o.order_no like :keywordPrefix escape '!'
                or o.resource_type like :keywordPrefix escape '!'
                or o.resource_code like :keywordPrefix escape '!'
                or o.contact_phone like :keywordPrefix escape '!'
                or o.invoice_status like :keywordPrefix escape '!'
                or o.registration_status like :keywordPrefix escape '!'
                or o.contract_type like :keywordPrefix escape '!'
                or (:fullTextKeyword is not null
                    and match(
                      o.resource_name,
                      o.specification_model,
                      o.customer_name,
                      o.customer_address,
                      o.contact_name,
                      o.operator,
                      o.payment_remark,
                      o.invoice_original_name,
                      o.contract_original_name,
                      o.order_remark
                    ) against (:fullTextKeyword in boolean mode)))
            order by o.created_at desc, o.id desc
            """,
            countQuery = """
            select count(*)
            from outbound_order o
            where (:includeLocked = true or coalesce(o.is_locked, 0) = 0)
              and (
                :stage is null
                or (:stage = 'payment'
                    and (o.payment_due_date is null or o.payment_due_date >= current_date)
                    and coalesce(o.receivable_amount, o.settlement_price, 0) - coalesce(o.received_amount, 0) > 0)
                or (:stage = 'overdue'
                    and o.payment_due_date is not null and o.payment_due_date < current_date
                    and coalesce(o.receivable_amount, o.settlement_price, 0) - coalesce(o.received_amount, 0) > 0)
                or (:stage = 'salesReport' and coalesce(o.payment_settled, 0) = 1 and coalesce(o.sales_reported, 0) = 0)
                or (:stage = 'invoiceApplication' and coalesce(o.payment_settled, 0) = 1 and coalesce(o.sales_reported, 0) = 1 and coalesce(o.invoice_applied, 0) = 0)
                or (:stage = 'invoiceFile' and (
                      coalesce(o.invoice_applied, 0) = 1 or o.invoice_issued_date is not null
                      or lower(coalesce(o.invoice_status, '')) like '%issued%'
                      or lower(coalesce(o.invoice_status, '')) like '%invoiced%'
                      or coalesce(o.invoice_status, '') like '%开票完成%'
                      or coalesce(o.invoice_status, '') like '%完成开票%'
                      or coalesce(o.invoice_status, '') like '%已出票%'
                    ) and coalesce(o.invoice_stored_file_name, '') = '')
                or (:stage = 'contractFile' and o.contract_type is not null and trim(o.contract_type) <> ''
                    and lower(trim(o.contract_type)) not like 'no%' and lower(trim(o.contract_type)) not like 'none%'
                    and lower(trim(o.contract_type)) not like 'false%' and trim(o.contract_type) not like '否%'
                    and trim(o.contract_type) not like '无%' and trim(o.contract_type) not like '没%'
                    and trim(o.contract_type) not like '不%' and trim(o.contract_type) not like '%无合同%'
                    and coalesce(o.contract_stored_file_name, '') = '')
                or (:stage = 'closed' and coalesce(o.payment_settled, 0) = 1 and coalesce(o.sales_reported, 0) = 1
                    and coalesce(o.invoice_applied, 0) = 1 and coalesce(o.invoice_stored_file_name, '') <> '')
              )
              and (:keywordPrefix is null
                or o.order_no like :keywordPrefix escape '!'
                or o.resource_type like :keywordPrefix escape '!'
                or o.resource_code like :keywordPrefix escape '!'
                or o.contact_phone like :keywordPrefix escape '!'
                or o.invoice_status like :keywordPrefix escape '!'
                or o.registration_status like :keywordPrefix escape '!'
                or o.contract_type like :keywordPrefix escape '!'
                or (:fullTextKeyword is not null
                    and match(
                      o.resource_name,
                      o.specification_model,
                      o.customer_name,
                      o.customer_address,
                      o.contact_name,
                      o.operator,
                      o.payment_remark,
                      o.invoice_original_name,
                      o.contract_original_name,
                      o.order_remark
                    ) against (:fullTextKeyword in boolean mode)))
            """,
            nativeQuery = true)
    Page<OutboundOrder> searchPage(
            @Param("keywordPrefix") String keywordPrefix,
            @Param("fullTextKeyword") String fullTextKeyword,
            @Param("includeLocked") boolean includeLocked,
            @Param("stage") String stage,
            Pageable pageable
    );

    @Query(value = """
            select
              count(o.id) as totalCount,
              sum(case when coalesce(o.payment_settled, 0) = 1 then 0 else 1 end) as unsettledOrders,
              sum(case when coalesce(o.sales_reported, 0) = 1 then 0 else 1 end) as pendingReports,
              sum(case when coalesce(o.invoice_applied, 0) = 1 then 0 else 1 end) as pendingInvoices,
              sum(case when coalesce(o.payment_settled, 0) = 1 then 1 else 0 end) as settledOrders,
              sum(case when o.invoice_stored_file_name is null or o.invoice_stored_file_name = '' then 0 else 1 end) as uploadedInvoices,
              sum(case when o.contract_stored_file_name is null or o.contract_stored_file_name = '' then 0 else 1 end) as uploadedContracts,
              sum(case when o.resource_type = 'MACHINE' then 1 else 0 end) as machineOrders,
              sum(case when coalesce(o.receivable_amount, o.settlement_price, 0) - coalesce(o.received_amount, 0) > 0
                       then coalesce(o.receivable_amount, o.settlement_price, 0) - coalesce(o.received_amount, 0)
                       else 0 end) as outstandingAmount,
              sum(case when o.payment_due_date is not null
                            and o.payment_due_date < current_date
                            and coalesce(o.receivable_amount, o.settlement_price, 0) - coalesce(o.received_amount, 0) > 0
                       then 1 else 0 end) as overdueOrders
            from outbound_order o
            where (:includeLocked = true or coalesce(o.is_locked, 0) = 0)
              and (:keywordPrefix is null
                or o.order_no like :keywordPrefix escape '!'
                or o.resource_type like :keywordPrefix escape '!'
                or o.resource_code like :keywordPrefix escape '!'
                or o.contact_phone like :keywordPrefix escape '!'
                or o.invoice_status like :keywordPrefix escape '!'
                or o.registration_status like :keywordPrefix escape '!'
                or o.contract_type like :keywordPrefix escape '!'
                or (:fullTextKeyword is not null
                    and match(
                      o.resource_name,
                      o.specification_model,
                      o.customer_name,
                      o.customer_address,
                      o.contact_name,
                      o.operator,
                      o.payment_remark,
                      o.invoice_original_name,
                      o.contract_original_name,
                      o.order_remark
                    ) against (:fullTextKeyword in boolean mode)))
            """,
            nativeQuery = true)
    OutboundOrderSummaryProjection summarize(
            @Param("keywordPrefix") String keywordPrefix,
            @Param("fullTextKeyword") String fullTextKeyword,
            @Param("includeLocked") boolean includeLocked
    );

    @Query("""
            select
              sum(coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0)) as receivableAmount,
              sum(coalesce(o.receivedAmount, 0)) as receivedAmount,
              sum(case when coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0) > 0
                       then coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0)
                       else 0 end) as outstandingAmount,
              sum(case when coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0) > 0
                       then 1 else 0 end) as pendingPaymentCount,
              sum(case when o.paymentDueDate is not null
                            and o.paymentDueDate < current_date
                            and coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0) > 0
                       then 1 else 0 end) as overduePaymentCount,
              sum(case when o.paymentDueDate is not null
                            and o.paymentDueDate < current_date
                            and coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0) > 0
                       then coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0)
                       else 0 end) as overdueAmount,
              sum(case when o.paymentSettled = true and (o.salesReported is null or o.salesReported = false)
                       then 1 else 0 end) as pendingSalesReportCount,
              sum(case when o.paymentSettled = true
                            and o.salesReported = true
                            and (o.invoiceApplied is null or o.invoiceApplied = false)
                       then 1 else 0 end) as pendingInvoiceApplicationCount,
              sum(case when (
                            o.invoiceApplied = true
                            or o.invoiceIssuedDate is not null
                            or lower(coalesce(o.invoiceStatus, '')) like '%issued%'
                            or lower(coalesce(o.invoiceStatus, '')) like '%invoiced%'
                            or coalesce(o.invoiceStatus, '') like '%\u5f00\u7968\u5b8c\u6210%'
                            or coalesce(o.invoiceStatus, '') like '%\u5b8c\u6210\u5f00\u7968%'
                            or coalesce(o.invoiceStatus, '') like '%\u5df2\u51fa\u7968%'
                          )
                          and (o.invoiceStoredFileName is null or o.invoiceStoredFileName = '')
                       then 1 else 0 end) as pendingInvoiceFileCount,
              sum(case when o.contractType is not null
                            and trim(o.contractType) <> ''
                            and lower(trim(o.contractType)) not like 'no%'
                            and lower(trim(o.contractType)) not like 'none%'
                            and lower(trim(o.contractType)) not like 'false%'
                            and trim(o.contractType) not like '\u5426%'
                            and trim(o.contractType) not like '\u65e0%'
                            and trim(o.contractType) not like '\u6ca1%'
                            and trim(o.contractType) not like '\u4e0d%'
                            and trim(o.contractType) not like '%\u65e0\u5408\u540c%'
                            and (o.contractStoredFileName is null or o.contractStoredFileName = '')
                       then 1 else 0 end) as pendingContractFileCount
            from OutboundOrder o
            where (:includeLocked = true or o.isLocked = false)
            """)
    OutboundTodoSummaryProjection summarizeTodos(@Param("includeLocked") boolean includeLocked);

    @Query("""
            select o from OutboundOrder o
            where (:includeLocked = true or o.isLocked = false)
              and o.paymentDueDate is not null
              and o.paymentDueDate < current_date
              and coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0) > 0
            order by o.updatedAt desc, o.id desc
            """)
    List<OutboundOrder> findOverduePaymentTodos(@Param("includeLocked") boolean includeLocked, Pageable pageable);

    @Query("""
            select o from OutboundOrder o
            where (:includeLocked = true or o.isLocked = false)
              and (o.paymentDueDate is null or o.paymentDueDate >= current_date)
              and coalesce(coalesce(coalesce(o.receivableAmount, o.lineAmount), o.settlementPrice), 0) - coalesce(o.receivedAmount, 0) > 0
            order by o.updatedAt desc, o.id desc
            """)
    List<OutboundOrder> findPendingPaymentTodos(@Param("includeLocked") boolean includeLocked, Pageable pageable);

    @Query("""
            select o from OutboundOrder o
            where (:includeLocked = true or o.isLocked = false)
              and o.paymentSettled = true
              and (o.salesReported is null or o.salesReported = false)
            order by o.updatedAt desc, o.id desc
            """)
    List<OutboundOrder> findSalesReportTodos(@Param("includeLocked") boolean includeLocked, Pageable pageable);

    @Query("""
            select o from OutboundOrder o
            where (:includeLocked = true or o.isLocked = false)
              and o.paymentSettled = true
              and o.salesReported = true
              and (o.invoiceApplied is null or o.invoiceApplied = false)
            order by o.updatedAt desc, o.id desc
            """)
    List<OutboundOrder> findInvoiceApplicationTodos(@Param("includeLocked") boolean includeLocked, Pageable pageable);

    @Query("""
            select o from OutboundOrder o
            where (:includeLocked = true or o.isLocked = false)
              and (
                o.invoiceApplied = true
                or o.invoiceIssuedDate is not null
                or lower(coalesce(o.invoiceStatus, '')) like '%issued%'
                or lower(coalesce(o.invoiceStatus, '')) like '%invoiced%'
                or coalesce(o.invoiceStatus, '') like '%\u5f00\u7968\u5b8c\u6210%'
                or coalesce(o.invoiceStatus, '') like '%\u5b8c\u6210\u5f00\u7968%'
                or coalesce(o.invoiceStatus, '') like '%\u5df2\u51fa\u7968%'
              )
              and (o.invoiceStoredFileName is null or o.invoiceStoredFileName = '')
            order by o.updatedAt desc, o.id desc
            """)
    List<OutboundOrder> findInvoiceFileTodos(@Param("includeLocked") boolean includeLocked, Pageable pageable);

    @Query("""
            select o from OutboundOrder o
            where (:includeLocked = true or o.isLocked = false)
              and o.contractType is not null
              and trim(o.contractType) <> ''
              and lower(trim(o.contractType)) not like 'no%'
              and lower(trim(o.contractType)) not like 'none%'
              and lower(trim(o.contractType)) not like 'false%'
              and trim(o.contractType) not like '\u5426%'
              and trim(o.contractType) not like '\u65e0%'
              and trim(o.contractType) not like '\u6ca1%'
              and trim(o.contractType) not like '\u4e0d%'
              and trim(o.contractType) not like '%\u65e0\u5408\u540c%'
              and (o.contractStoredFileName is null or o.contractStoredFileName = '')
            order by o.updatedAt desc, o.id desc
            """)
    List<OutboundOrder> findContractFileTodos(@Param("includeLocked") boolean includeLocked, Pageable pageable);

    @Query("""
            select o from OutboundOrder o
            where (
                coalesce(o.invoiceStoredFileName, '') <> ''
                and not exists (
                    select 1 from ResourceAttachment a
                    where a.resourceType = 'OUTBOUND_ORDER'
                      and a.resourceId = o.id
                      and a.attachmentCategory = 'INVOICE'
                      and a.deleted = false
                )
            ) or (
                coalesce(o.contractStoredFileName, '') <> ''
                and not exists (
                    select 1 from ResourceAttachment a
                    where a.resourceType = 'OUTBOUND_ORDER'
                      and a.resourceId = o.id
                      and a.attachmentCategory = 'CONTRACT'
                      and a.deleted = false
                )
            )
            order by o.id asc
            """)
    List<OutboundOrder> findLegacyAttachmentBackfillCandidates(Pageable pageable);

    Optional<OutboundOrder> findByIdAndIsLockedFalse(Long id);

    List<OutboundOrder> findByResourceTypeAndResourceIdOrderByCreatedAtDesc(String resourceType, Long resourceId);

    boolean existsByResourceTypeAndResourceIdAndIsLockedTrueAndIdNot(
            String resourceType,
            Long resourceId,
            Long id
    );

    boolean existsByCustomerId(Long customerId);

    boolean existsByResourceTypeAndResourceId(String resourceType, Long resourceId);

    boolean existsBySourceWarehouseId(Long sourceWarehouseId);

    boolean existsByOrderNo(String orderNo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OutboundOrder o where o.id = :id")
    Optional<OutboundOrder> findByIdForUpdate(@Param("id") Long id);

    interface OutboundOrderSummaryProjection {
        Long getTotalCount();
        Long getUnsettledOrders();
        Long getPendingReports();
        Long getPendingInvoices();
        Long getSettledOrders();
        Long getUploadedInvoices();
        Long getUploadedContracts();
        Long getMachineOrders();
        BigDecimal getOutstandingAmount();
        Long getOverdueOrders();
    }

    interface OutboundTodoSummaryProjection {
        BigDecimal getReceivableAmount();
        BigDecimal getReceivedAmount();
        BigDecimal getOutstandingAmount();
        Long getPendingPaymentCount();
        Long getOverduePaymentCount();
        BigDecimal getOverdueAmount();
        Long getPendingSalesReportCount();
        Long getPendingInvoiceApplicationCount();
        Long getPendingInvoiceFileCount();
        Long getPendingContractFileCount();
    }
}
