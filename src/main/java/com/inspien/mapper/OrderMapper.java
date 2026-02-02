package com.inspien.mapper;

import com.inspien.dto.OrderDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderMapper {

    String selectMaxOrderId(@Param("applicantKey") String applicantKey);

    int insertOrders(@Param("rows") List<OrderDTO> rows);
}
