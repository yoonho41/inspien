package com.inspien.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class ShipmentDTO {
    private String shipmentId;
    private String orderId;
    private String itemId;
    private String applicantKey;
    private String address;
}
