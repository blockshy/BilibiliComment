package com.hy.mybatis.mapper;

import com.hy.mybatis.entity.TaskController;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface TaskControllerMapper {

    @Select("SELECT * FROM task_controller WHERE id = #{id}")
    TaskController findById(Integer id);

    @Select("SELECT * FROM task_controller WHERE enabled = 1")
    List<TaskController> findAll();

    @Insert("INSERT INTO task_controller (task_name, task_type, enabled, task_remark, cron_expression) " +
            "VALUES (#{taskName}, #{taskType}, #{enabled}, #{taskRemark}, #{cronExpression})")
    void insert(TaskController taskController);

    @Update("UPDATE task_controller SET task_name = #{taskName}, task_type = #{taskType}, " +
            "enabled = #{enabled}, task_remark = #{taskRemark}, cron_expression = #{cronExpression} " +
            "WHERE id = #{id}")
    void update(TaskController taskController);

    @Delete("DELETE FROM task_controller WHERE id = #{id}")
    void delete(Integer id);
}
