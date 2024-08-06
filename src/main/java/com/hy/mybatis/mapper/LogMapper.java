package com.hy.mybatis.mapper;

import com.hy.mybatis.entity.Log;
import org.apache.ibatis.annotations.*;

@Mapper
public interface LogMapper {

    @Update("CREATE TABLE ${tableName}  (\n" +
            "  `id` int(11) NOT NULL AUTO_INCREMENT,\n" +
            "  `mid` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'UID',\n" +
            "  `uname` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '昵称',\n" +
            "  `avatar` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '头像',\n" +
            "  `current_level` int(11) NULL DEFAULT NULL COMMENT '用户等级',\n" +
            "  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL COMMENT '评论内容',\n" +
            "  `ctime` datetime NULL DEFAULT NULL COMMENT '评论时间',\n" +
            "  `rpid` bigint(20) NULL DEFAULT NULL COMMENT '评论ID',\n" +
            "  `parent` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '父评论ID',\n" +
            "  PRIMARY KEY (`id`) USING BTREE\n" +
            ") ENGINE = InnoDB AUTO_INCREMENT = 21965 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci ROW_FORMAT = Dynamic;")
    void createTable(@Param("tableName") String tableName);

    @Insert("INSERT INTO ${tableName} (`mid`, `uname`, `avatar`, `current_level`, `content`, `ctime`, `rpid`, `parent`) " +
            "VALUES (#{log.mid}, #{log.uname}, #{log.avatar}, #{log.currentLevel}, #{log.content}, #{log.ctime}, #{log.rpid}, #{log.parent})")
    void insertLog(@Param("log") Log log, @Param("tableName") String tableName);

    @Select("SELECT COUNT(1) from ${tableName} WHERE rpid = #{rpid}")
    Integer selectRpId(@Param("tableName") String tableName, @Param("rpid") long rpid);

    //存在为1
    @Select("SELECT EXISTS ( SELECT 1 FROM information_schema.tables WHERE table_schema = 'bilibili_comment' AND table_name = #{tableName}) AS table_exists")
    Integer checkTable(@Param("tableName") String tableName);

    @Update("update ${tableName} set allLog = 1")
    void updateTask(@Param("tableName") String tableName);
}
