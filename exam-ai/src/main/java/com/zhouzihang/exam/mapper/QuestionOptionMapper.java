package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.QuestionOption;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 选项表访问。 */
@Mapper
public interface QuestionOptionMapper {

    @Insert("insert into question_option(question_id, option_key, content) values(#{questionId}, #{optionKey}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(QuestionOption option);

    @Select("select * from question_option where question_id = #{questionId} order by option_key")
    List<QuestionOption> findByQuestionId(@Param("questionId") long questionId);

    @Select("<script>select * from question_option where question_id in "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>"
            + " order by question_id, option_key</script>")
    List<QuestionOption> findByQuestionIds(@Param("ids") List<Long> ids);
}
