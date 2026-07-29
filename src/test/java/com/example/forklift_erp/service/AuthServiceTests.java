package com.example.forklift_erp.service;

import com.example.forklift_erp.common.ResultCode;
import com.example.forklift_erp.entity.User;
import com.example.forklift_erp.exception.BusinessException;
import com.example.forklift_erp.repository.RepairRecordRepository;
import com.example.forklift_erp.repository.RoleRepository;
import com.example.forklift_erp.repository.UserRepository;
import com.example.forklift_erp.security.JwtTokenProvider;
import com.example.forklift_erp.security.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AuthServiceTests {

    @Test
    void deleteUserWithRepairHistoryRequiresAccountDeactivation() {
        UserRepository userRepository = mock(UserRepository.class);
        RepairRecordRepository repairRecordRepository = mock(RepairRecordRepository.class);
        CollaborationService collaborationService = mock(CollaborationService.class);
        OperationAuditService operationAuditService = mock(OperationAuditService.class);
        Authentication authentication = mock(Authentication.class);
        User target = new User();
        target.setId(10L);
        target.setVersion(3L);
        target.setUsername("repair-worker");
        when(authentication.getName()).thenReturn("supervisor");
        when(userRepository.findByIdForUpdate(10L)).thenReturn(Optional.of(target));
        when(repairRecordRepository.existsByRepairPersonUserId(10L)).thenReturn(true);

        AuthService service = new AuthService(
                userRepository,
                mock(RoleRepository.class),
                mock(PasswordEncoder.class),
                mock(JwtTokenProvider.class),
                mock(PermissionService.class),
                operationAuditService,
                collaborationService,
                repairRecordRepository
        );

        assertThatThrownBy(() -> service.deleteUser(10L, 3L, authentication))
                .isInstanceOf(BusinessException.class)
                .satisfies(error -> assertThat(((BusinessException) error).getCode())
                        .isEqualTo(ResultCode.CONFLICT.getCode()))
                .hasMessageContaining("disable the account instead");

        verify(collaborationService).validateWrite(target, 3L);
        verify(userRepository, never()).delete(target);
        verifyNoInteractions(operationAuditService);
    }
}
