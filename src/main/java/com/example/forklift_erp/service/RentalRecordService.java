package com.example.forklift_erp.service;

import com.example.forklift_erp.common.PageResult;
import com.example.forklift_erp.dto.RentalRecordCreateDTO;
import com.example.forklift_erp.dto.RentalRecordUpdateDTO;
import com.example.forklift_erp.dto.RentalRecordVO;
import com.example.forklift_erp.dto.RentalBillVO;

import java.util.List;

public interface RentalRecordService {
    List<RentalRecordVO> findAll();

    PageResult<RentalRecordVO> findPage(String keyword, Integer page, Integer size);

    PageResult<RentalRecordVO> findPage(String keyword, String status, Integer page, Integer size);

    RentalRecordVO findById(Long id);

    List<RentalBillVO> findBills(Long id);

    RentalRecordVO create(RentalRecordCreateDTO request);

    RentalRecordVO update(Long id, RentalRecordUpdateDTO request);

    void delete(Long id, Long version);
}
