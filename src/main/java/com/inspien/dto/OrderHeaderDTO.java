package com.inspien.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class OrderHeaderDTO {
    private String userId;
    private String name;
    private String address;
    private String status;
}
