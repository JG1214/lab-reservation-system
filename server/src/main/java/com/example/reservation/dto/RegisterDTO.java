package com.example.reservation.dto;

import lombok.Data;

/**
 * 注册请求参数（学生 / 教师）
 * 校验规则：账号唯一、两次密码一致、密码长度 ≥ 6、学生角色必填学号
 *
 * @author reservation-team
 */
@Data
public class RegisterDTO {

    /** 登录账号 */
    private String username;

    /** 密码 */
    private String password;

    /** 确认密码 */
    private String confirmPassword;

    /** 真实姓名 */
    private String name;

    /** 学号（学生角色必填） */
    private String studentNo;

    /** 联系电话 */
    private String phone;

    /** 邮箱 */
    private String email;

    /**
     * 注册角色：0-学生（默认）、2-教师；管理员为系统预置，注册接口不提供
     *
     * <p>需求设计文档 V4.0 1.3「公共功能」：学生/教师可自主注册；2.4 公共页面第 2 条：
     * 角色选择仅学生/教师，不提供管理员选项。
     */
    private Integer role;
}
