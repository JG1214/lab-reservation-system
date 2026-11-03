package com.example.reservation.dto;

import lombok.Data;

/**
 * 管理员修改用户角色请求参数（需求文档 V4.0 1.2）
 *
 * @author reservation-team
 */
@Data
public class UserRoleDTO {

    /** 目标角色：0-学生，1-实验室管理员，2-教师 */
    private Integer role;
}
