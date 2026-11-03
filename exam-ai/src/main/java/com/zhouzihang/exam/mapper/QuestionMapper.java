package com.zhouzihang.exam.mapper;

import com.zhouzihang.exam.domain.Question;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 题目表访问。 */
@Mapper
public interface QuestionMapper {

    @Insert("insert into question(exam_id, question_type, stem, knowledge_point, difficulty, score, correct_option, analysis, source) "
            + "values(#{examId}, #{questionType}, #{stem}, #{knowledgePoint}, #{difficulty}, #{score}, #{correctOption}, #{analysis}, #{source})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Question question);

    @Select("select * from question where id = #{id}")
    Question findById(@Param("id") long id);

    @Select("select * from question where exam_id = #{examId} order by id")
    List<Question> findByExamId(@Param("examId") long examId);

    /** 题库公共题（exam_id is null），供试卷无自有题目时兜底。 */
    @Select("select * from question where exam_id is null order by id limit #{limit}")
    List<Question> findBank(@Param("limit") int limit);

    @Select("select count(*) from question where exam_id = #{examId}")
    int countByExamId(@Param("examId") long examId);

    @Select("select count(*) from question where exam_id is null")
    int countBank();

    @Update("update question set exam_id = #{examId} where id = #{questionId}")
    int attachToExam(@Param("questionId") long questionId, @Param("examId") long examId);

    @Select("<script>select * from question where id in "
            + "<foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    List<Question> findByIds(@Param("ids") List<Long> ids);
}
