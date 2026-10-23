package com.doms.inventory.product;

import java.math.BigDecimal;
import java.util.UUID;

public record ProductResponse(UUID id, String name, BigDecimal price){
}
