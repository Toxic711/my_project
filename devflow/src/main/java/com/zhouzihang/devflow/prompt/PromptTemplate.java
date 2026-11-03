package com.zhouzihang.devflow.prompt;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Prompt 模板（表 prompt_template）。
 *
 * <p>面试讲解点：模板"版本化"而不是"就地覆盖"。原因：
 * <ol>
 *   <li>可回滚——线上某个 prompt 改坏了能定位到具体版本；</li>
 *   <li>可对比——同一 code 的 v1/v2 能 A/B 或做效果评估；</li>
 *   <li>可审计——谁在什么时候改了哪句话。</li>
 * </ol>
 * 唯一键是 {@code (code, version)}，所以并发写版本号由数据库保证不重复
 * （冲突时由 Service 抛 409 让客户端重试）。</p>
 */
@Data
@TableName("prompt_template")
public class PromptTemplate {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 业务标识，如 chat / sql-generate / code-review。 */
    private String code;

    private String name;

    /** 模板正文，含 ${var} 占位符。 */
    private String content;

    /** 从 content 中解析出的变量名，逗号分隔（冗余字段，便于列表展示与校验）。 */
    private String variables;

    private Integer version;

    /** 1=启用。 */
    private Integer enabled;

    /** 逻辑删除标记（MyBatis-Plus 全局 logic-delete-field）。 */
    private Integer deleted;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
