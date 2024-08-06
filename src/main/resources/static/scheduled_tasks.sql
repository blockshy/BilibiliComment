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

 Date: 03/08/2024 00:16:46
*/

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ----------------------------
-- Table structure for scheduled_tasks
-- ----------------------------
DROP TABLE IF EXISTS `scheduled_tasks`;
CREATE TABLE `scheduled_tasks`  (
  `id` bigint(20) NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `task_name` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT '任务名称',
  `cron_expression` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NOT NULL COMMENT 'Cron 表达式，用于定义任务的执行计划',
  `enabled` tinyint(1) NULL DEFAULT 1 COMMENT '任务是否启用',
  `last_execution` timestamp NULL DEFAULT NULL COMMENT '上次任务执行时间',
  `next_execution` timestamp NULL DEFAULT NULL COMMENT '下次预定执行时间',
  `created_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录创建时间',
  `updated_at` timestamp NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '记录最后更新时间',
  `remarks` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '备注',
  `bv_no` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '对应的bv号',
  `mark_no` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '对应的评论ID',
  `all_log` int(5) NULL DEFAULT NULL COMMENT '是否全量查询 1是 0否',
  `type` int(5) NULL DEFAULT NULL COMMENT '类型 1视频 2评论',
  `url` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '后续获取评论URL',
  `request_info_id` int(11) NULL DEFAULT NULL COMMENT '对应请求参数配置',
  PRIMARY KEY (`id`) USING BTREE
) ENGINE = InnoDB AUTO_INCREMENT = 8 CHARACTER SET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '存储定时任务配置信息的表' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
