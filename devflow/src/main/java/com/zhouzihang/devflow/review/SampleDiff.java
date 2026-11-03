package com.zhouzihang.devflow.review;

/**
 * 内置示例 diff（给 {@code GET /api/review/sample} 与前端"一键体验"用）。
 *
 * <p>样本刻意包含 4 类典型问题：字符串拼接 SQL、硬编码密钥、无条件 DELETE、
 * 吞异常 + 阻塞睡眠 + 调试输出。它们是代码审查里最常见的"真实缺陷"，
 * 便于演示结构化 findings 与严重级别统计。</p>
 */
public final class SampleDiff {

    private SampleDiff() {
    }

    /** 示例 unified diff 文本。 */
    public static final String TEXT = """
            diff --git a/src/main/java/com/acme/order/OrderService.java b/src/main/java/com/acme/order/OrderService.java
            index 3f1a2b4..9c8d7e6 100644
            --- a/src/main/java/com/acme/order/OrderService.java
            +++ b/src/main/java/com/acme/order/OrderService.java
            @@ -21,3 +21,5 @@ public class OrderService {
                 public Order findById(Long id) {
            -        return repo.findById(id).orElse(null);
            +        String sql = "SELECT * FROM orders WHERE id = " + id;
            +        return jdbcTemplate.queryForObject(sql, Order.class);
                 }
            @@ -30,2 +32,4 @@ public class OrderService {
            +    public void purgeAll() {
            +        String apiKey = "sk-live-8f2a1c9d";
            +        jdbcTemplate.update("DELETE FROM orders");
            +    }
            diff --git a/src/main/java/com/acme/user/UserController.java b/src/main/java/com/acme/user/UserController.java
            index 5a6b7c8..1d2e3f4 100644
            --- a/src/main/java/com/acme/user/UserController.java
            +++ b/src/main/java/com/acme/user/UserController.java
            @@ -42,4 +42,9 @@ public class UserController {
                 public User load(String id) {
            +        try {
            +            Thread.sleep(200);
            +            System.out.println("loading user " + id);
            +            return service.load(id);
            +        } catch (Exception e) {
            +            return null;
            +        }
                 }
            """;

    /** 示例说明（前端展示用）。 */
    public static final String DESCRIPTION =
            "内置示例 diff：包含 SQL 字符串拼接、硬编码密钥、无条件 DELETE、吞异常、"
                    + "阻塞睡眠与调试输出等典型问题，用于演示结构化审查结果。";
}
