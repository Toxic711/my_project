package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.Answer;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 作答明细表访问。 */
@Mapper
public interface AnswerMapper {

    @Insert("insert into answer(exam_record_id, question_id, answer_value, correct_flag, score, answered_at) "
            + "values(#{examRecordId}, #{questionId}, #{answerValue}, #{correctFlag}, #{score}, #{answeredAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Answer answer);

    @Select("select * from answer where exam_record_id = #{recordId} order by question_id")
    List<Answer> findByRecordId(@Param("recordId") long recordId);

    @Select("select * from answer where exam_record_id = #{recordId} and question_id = #{questionId}")
    Answer findByRecordAndQuestion(@Param("recordId") long recordId, @Param("questionId") long questionId);

    @Update("update answer set answer_value = #{answerValue}, correct_flag = #{correctFlag}, "
            + "score = #{score}, answered_at = #{answeredAt} where id = #{id}")
    int update(Answer answer);
}
