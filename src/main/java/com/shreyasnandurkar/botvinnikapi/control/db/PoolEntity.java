package com.shreyasnandurkar.botvinnikapi.control.db;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

@Table("pools")
public record PoolEntity(
        @Id UUID id,
        String name,
        String strategy) {
}
