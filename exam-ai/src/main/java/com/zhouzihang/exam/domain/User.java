package com.zhouzihang.exam.domain;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户（简历口径的 10 张表中的第 1 张）。
 *
 * <p>面试讲解点：简历写的是 sys_user + sys_role 两张表，本项目把角色合并为
 * user.role 单列（ADMIN/TEACHER/STUDENT 三级 RBAC），用一张表表达 10 张表的
 * 精简模型。取舍：省掉用户-角色多对多中间表，适合“一账号一角色”的校园场景；
 * 若要做“一人多角色”，再加 sys_user_role 即可，查询用 exists 判断。</p>
 */
@Data
public class User {
    private Long id;
    private String username;
    /** PBKDF2 哈希，绝不明文；格式 pbkdf2$iterations$salt$hash。 */
    private String passwordHash;
    private String realName;
    /** ADMIN / TEACHER / STUDENT */
    private String role;
    /** 1 启用，0 禁用（H2/MySQL 用 TINYINT，实体用 Integer 避免布尔映射差异）。 */
    private Integer enabled;
    private LocalDateTime createdAt;
}
