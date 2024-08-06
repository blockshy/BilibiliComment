/*
 Navicat Premium Data Transfer

 Source Server         : MySQL5
 Source Server Type    : MySQL
 Source Server Version : 50744 (5.7.44)
 Source Host           : localhost:3306
 Source Schema         : bilibili_comment

 Target Server Type    : MySQL
 Target Server Version : 50744 (5.7.44)
 File Encoding         : 65001

 Date: 04/08/2024 22:37:31
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for scheduled_tasks_all_log
-- ----------------------------
DROP TABLE IF EXISTS `scheduled_tasks_all_log`;
CREATE TABLE `scheduled_tasks_all_log`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `task_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务名称',
  `enabled` tinyint(1) NULL DEFAULT 1 COMMENT '任务是否启用',
  `remarks` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  `bv_no` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '对应的bv号',
  `all_log` int(5) NULL DEFAULT NULL COMMENT '是否全量查询 1是 0否',
  `type` int(5) NULL DEFAULT NULL COMMENT '类型 1视频 2评论',
  `url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '后续获取评论URL',
  `request_info_id` int(11) NULL DEFAULT NULL COMMENT '对应请求参数配置',
  `last_cursor` int(11) NULL DEFAULT NULL COMMENT '最近一次请求的cursor',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 77 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '存储定时任务配置信息的表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
