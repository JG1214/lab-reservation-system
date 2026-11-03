package com.example.reservation.dto;

import lombok.Data;

/**
 * 管理员新建用户请求参数（需求文档 V4.0 1.2：教师账号由管理员创建）
 * 校验规则：账号唯一、密码长度 ≥ 6、角色 ∈ {0学生, 1管理员, 2教师}、学生必须填学号
 *
 * @author reservation-team
 */
@Data
public class UserCreateDTO {

    /** 登录账号 */
    private String username;

    /** 初始密码（不填则使用默认密码 123456） */
    private String password;

    /** 真实姓名 */
    private String name;

    /** 角色：0-学生，1-实验室管理员，2-教师 */
    private Integer role;

    /** 学号（学生角色必填，教师/管理员可为空） */
    private String studentNo;

    /** 联系电话 */
    private String phone;

    /** 邮箱 */
    private String email;
}
