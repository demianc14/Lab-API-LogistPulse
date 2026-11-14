package com.logistpulse.lab.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.logistpulse.lab.api.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Sort;

/** Lista blanca del parámetro ?sort= (Parte 3). */
class OrderSortTest {

    @Test
    void defaultIsIdDescending() {
        assertThat(OrderService.parseSort(null)).isEqualTo(Sort.by("id").descending());
        assertThat(OrderService.parseSort("  ")).isEqualTo(Sort.by("id").descending());
    }

    @Test
    void publicNameIsMappedToEntityProperty() {
        Sort sort = OrderService.parseSort("customer,asc");
        assertThat(sort.getOrderFor("customerName")).isNotNull();
        assertThat(sort.getOrderFor("customerName").isAscending()).isTrue();
        assertThat(sort.getOrderFor("id")).isNotNull();   // desempate para paginar de forma estable
    }

    @Test
    void directionDefaultsToAscAndIsCaseInsensitive() {
        assertThat(OrderService.parseSort("createdAt").getOrderFor("createdAt").isAscending()).isTrue();
        assertThat(OrderService.parseSort("createdAt,DESC").getOrderFor("createdAt").isDescending()).isTrue();
    }

    @Test
    void rejectsFieldsOutsideTheWhitelist() {
        assertThatThrownBy(() -> OrderService.parseSort("password,desc"))
                .isInstanceOf(ApiException.class).extracting(e -> ((ApiException) e).getStatus()).isEqualTo(400);
        assertThatThrownBy(() -> OrderService.parseSort("warehouse")).isInstanceOf(ApiException.class);
    }

    @Test
    void rejectsBadDirectionOrExtraParts() {
        assertThatThrownBy(() -> OrderService.parseSort("id,sideways")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> OrderService.parseSort("id,asc,x")).isInstanceOf(ApiException.class);
    }
}
