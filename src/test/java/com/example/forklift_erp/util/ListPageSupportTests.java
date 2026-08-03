package com.example.forklift_erp.util;

import com.example.forklift_erp.common.PageResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ListPageSupportTests {

    @Test
    void normalizesInvalidPageArguments() {
        PageResult<Integer> page = ListPageSupport.page(List.of(1, 2, 3, 4, 5), -1, 2);

        assertThat(page.getContent()).containsExactly(1, 2);
        assertThat(page.getPage()).isZero();
        assertThat(page.getSize()).isEqualTo(2);
        assertThat(page.getTotalElements()).isEqualTo(5);
        assertThat(page.getTotalPages()).isEqualTo(3);
        assertThat(page.isFirst()).isTrue();
        assertThat(page.isLast()).isFalse();
    }

    @Test
    void filtersRowsCaseInsensitivelyAcrossProvidedText() {
        List<String> rows = List.of("CPCD30", "CBD15", "FD35");

        List<String> filtered = ListPageSupport.filter(rows, " cpc ",
                row -> ListPageSupport.text(row, "forklift"));

        assertThat(filtered).containsExactly("CPCD30");
    }

    @Test
    void createsNormalizedPageRequestWithMaximumSize() {
        var pageRequest = ListPageSupport.pageRequest(-2, 500);

        assertThat(pageRequest.getPageNumber()).isZero();
        assertThat(pageRequest.getPageSize()).isEqualTo(200);
    }

    @Test
    void largePageNumberDoesNotOverflowTheInMemoryOffset() {
        PageResult<Integer> page = ListPageSupport.page(List.of(1, 2, 3), Integer.MAX_VALUE, 200);

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getPage()).isEqualTo(Integer.MAX_VALUE);
        assertThat(page.isLast()).isTrue();
    }
}
