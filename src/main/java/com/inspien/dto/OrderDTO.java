package com.inspien.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OrderDTO {
    private String orderId;
    private String userId;
    private String itemId;
    private String applicantKey;
    private String name;
    private String address;
    private String itemName;
    private String price;
    private String status;
}
