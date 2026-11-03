package com.example.reservation.vo;

import com.alibaba.excel.annotation.ExcelProperty;
import com.alibaba.excel.annotation.write.style.ColumnWidth;
import lombok.Data;

import java.time.format.DateTimeFormatter;

/**
 * 使用记录导出对象（EasyExcel）
 *
 * <p>导出列与页面表格同序，登记时间统一格式化为 yyyy-MM-dd HH:mm，避免 Excel 读取为序列号。
 *
 * @author reservation-team
 */
@Data
public class UsageExportVO {

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @ExcelProperty("使用日期")
    @ColumnWidth(14)
    private String useDate;

    @ExcelProperty("时段")
    @ColumnWidth(16)
    private String period;

    @ExcelProperty("实验室")
    @ColumnWidth(24)
    private String classroom;

    @ExcelProperty("使用人")
    @ColumnWidth(14)
    private String realName;

    @ExcelProperty("账号")
    @ColumnWidth(16)
    private String username;

    @ExcelProperty("预约人数")
    @ColumnWidth(12)
    private String peopleCount;

    @ExcelProperty("实际人数")
    @ColumnWidth(12)
    private String actualPeople;

    @ExcelProperty("使用状态")
    @ColumnWidth(12)
    private String useStatusLabel;

    @ExcelProperty("登记备注")
    @ColumnWidth(30)
    private String checkRemark;

    @ExcelProperty("登记人")
    @ColumnWidth(14)
    private String registrarName;

    @ExcelProperty("登记时间")
    @ColumnWidth(20)
    private String checkTime;

    @ExcelProperty("预约用途")
    @ColumnWidth(30)
    private String purpose;

    /**
     * VO → 导出行
     */
    public static UsageExportVO of(UsageRecordVO vo) {
        UsageExportVO row = new UsageExportVO();
        row.setUseDate(vo.getUseDate() == null ? "" : vo.getUseDate().toString());
        row.setPeriod(vo.getStartTime() == null || vo.getEndTime() == null
                ? ""
                : vo.getStartTime() + "-" + vo.getEndTime());
        row.setClassroom(buildClassroom(vo));
        row.setRealName(vo.getRealName());
        row.setUsername(vo.getUsername());
        row.setPeopleCount(vo.getPeopleCount() == null ? "" : String.valueOf(vo.getPeopleCount()));
        row.setActualPeople(vo.getActualPeople() == null ? "" : String.valueOf(vo.getActualPeople()));
        row.setUseStatusLabel(vo.getUseStatusLabel());
        row.setCheckRemark(vo.getCheckRemark());
        row.setRegistrarName(vo.getRegistrarName());
        row.setCheckTime(vo.getCheckTime() == null ? "" : vo.getCheckTime().format(MINUTE));
        row.setPurpose(vo.getPurpose());
        return row;
    }

    private static String buildClassroom(UsageRecordVO vo) {
        StringBuilder sb = new StringBuilder();
        if (vo.getBuilding() != null) {
            sb.append(vo.getBuilding());
        }
        if (vo.getRoomNo() != null) {
            sb.append(' ').append(vo.getRoomNo());
        }
        if (vo.getClassroomName() != null) {
            sb.append(' ').append(vo.getClassroomName());
        }
        return sb.toString().trim();
    }
}
