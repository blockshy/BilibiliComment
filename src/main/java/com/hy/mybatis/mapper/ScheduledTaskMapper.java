package com.hy.mybatis.mapper;

import com.hy.mybatis.entity.ScheduledTask;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ScheduledTaskMapper {

    // 插入新任务
    @Insert("INSERT INTO scheduled_tasks (task_name, cron_expression, enabled, last_execution, next_execution, created_at, updated_at, remarks, bv_no, mark_no, all_log, type, url, request_info_id, params_type) " +
            "VALUES (#{taskName}, #{cronExpression}, #{enabled}, #{lastExecution}, #{nextExecution}, #{createdAt}, #{updatedAt}, #{remarks}, #{bvNo}, #{markNo}, #{allLog}, #{type}, #{url}, #{requestInfoId}, #{paramsType})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(ScheduledTask task);

    // 根据 ID 查询任务
    @Select("SELECT * FROM scheduled_tasks WHERE id = #{id}")
    ScheduledTask findById(Long id);

    // 查询所有任务
    @Select("SELECT * FROM scheduled_tasks where enabled = 1")
    List<ScheduledTask> findAll();

    // 更新任务
    @Update("UPDATE scheduled_tasks SET last_execution = #{lastExecution}, next_execution = #{nextExecution} WHERE id = #{id}")
    int update(ScheduledTask task);

    // 根据 ID 删除任务
    @Delete("DELETE FROM scheduled_tasks WHERE id = #{id}")
    int delete(Long id);

    // 更新URL
    @Update("UPDATE scheduled_tasks SET url = #{url} WHERE id = #{id}")
    int updateNewCommentUrl(@Param("id") Long id, @Param("url") String url);

    // 检查是否已存在任务
    @Select("SELECT COUNT(*) FROM scheduled_tasks WHERE mark_no = #{marksNo}")
    int checkExistsTaskByMarksNo(@Param("marksNo") String marksNo);

    // 检查是否已存在任务
    @Select("SELECT COUNT(*) FROM scheduled_tasks WHERE bv_no = #{bvNo}")
    int checkExistsTaskByBvNo(@Param("bvNo") String bvNo);
}
