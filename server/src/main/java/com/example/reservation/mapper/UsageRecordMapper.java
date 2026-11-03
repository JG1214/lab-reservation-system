package com.example.reservation.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.example.reservation.entity.UsageRecord;
import org.apache.ibatis.annotations.Mapper;

/**
 * 使用记录 Mapper（MyBatis-Plus 注解式，无 XML）
 *
 * @author reservation-team
 */
@Mapper
public interface UsageRecordMapper extends BaseMapper<UsageRecord> {
}
