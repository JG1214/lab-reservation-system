package com.example.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.reservation.entity.LabOpenSlot;
import org.apache.ibatis.annotations.Mapper;

/**
 * 实验室开放时段 Mapper
 *
 * @author reservation-team
 */
@Mapper
public interface LabOpenSlotMapper extends BaseMapper<LabOpenSlot> {
}
