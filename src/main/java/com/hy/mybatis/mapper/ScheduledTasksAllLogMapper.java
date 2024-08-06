package com.hy.mybatis.mapper;


import com.hy.mybatis.entity.ScheduledTasksAllLog;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ScheduledTasksAllLogMapper {

    @Insert("INSERT INTO scheduled_tasks_all_log (task_name, enabled, remarks, bv_no, all_log, type, url, request_info_id, last_cursor) " +
            "VALUES (#{taskName}, #{enabled}, #{remarks}, #{bvNo}, #{allLog}, #{type}, #{url}, #{requestInfoId}, #{lastCursor})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insert(ScheduledTasksAllLog scheduledTasksAllLog);

    @Select("SELECT * FROM scheduled_tasks_all_log WHERE id = #{id}")
    ScheduledTasksAllLog selectById(Long id);

    @Update("UPDATE scheduled_tasks_all_log SET task_name = #{taskName}, enabled = #{enabled}, remarks = #{remarks}, " +
            "bv_no = #{bvNo}, all_log = #{allLog}, type = #{type}, url = #{url}, request_info_id = #{requestInfoId}, " +
            "last_cursor = #{lastCursor} WHERE id = #{id}")
    void update(ScheduledTasksAllLog scheduledTasksAllLog);

    @Delete("DELETE FROM scheduled_tasks_all_log WHERE id = #{id}")
    void delete(Long id);

    @Select("SELECT * FROM scheduled_tasks_all_log where enabled = 1")
    List<ScheduledTasksAllLog> selectAll();

    @Update("UPDATE scheduled_tasks_all_log SET last_cursor = #{lastCursor} where id = #{id}")
    void updateLastCursor(@Param("id")Long id, @Param("lastCursor") Integer lastCursor);

    @Update("UPDATE scheduled_tasks_all_log SET enabled = 0 where id = #{id}")
    void updateEnabled(@Param("id")Long id);
}
