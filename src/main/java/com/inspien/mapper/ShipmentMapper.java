package com.inspien.mapper;

import com.inspien.dto.OrderDTO;
import com.inspien.dto.ShipmentDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ShipmentMapper {

    List<OrderDTO> selectUnsentOrdersForUpdate(
      @Param("applicantKey") String applicantKey,
      @Param("limit") int limit
    );

    int insertShipments(@Param("rows") List<ShipmentDTO> rows);

    int updateOrderStatusY(
      @Param("applicantKey") String applicantKey,
      @Param("orderIds") List<String> orderIds
    );
}
