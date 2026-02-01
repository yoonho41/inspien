package com.inspien.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OrderItemDTO {
    private String userId;
    private String itemId;
    private String itemName;
    private String price;
}
