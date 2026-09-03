-- 医疗买药问诊平台 · 问诊领域：初始化数据库 schema 并授权 shop 用户
-- 说明：本工程仅覆盖问诊领域；上游导购领域、下游订单领域的库由各自领域工程维护。

-- ===== 已实现服务 =====
CREATE DATABASE IF NOT EXISTS `shopdb_medicine` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 药品服务（:8081）
CREATE DATABASE IF NOT EXISTS `shopdb_consult`  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 问诊会话/IM/开方参考实现（:8085/8086）
CREATE DATABASE IF NOT EXISTS `shopdb_agent`    CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 多 Agent 智能体（:8087）

-- ===== 问诊领域占位服务（实现时启用）=====
CREATE DATABASE IF NOT EXISTS `shopdb_consult_record`     CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 问诊记录（:8090）
CREATE DATABASE IF NOT EXISTS `shopdb_prescription`       CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 开处方（:8091）
CREATE DATABASE IF NOT EXISTS `shopdb_prescription_mgmt`  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 处方管理（:8092）
CREATE DATABASE IF NOT EXISTS `shopdb_doctor_assign`      CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 分配医生（:8093）
CREATE DATABASE IF NOT EXISTS `shopdb_pharmacist_assign`  CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 分配药师（:8094）
CREATE DATABASE IF NOT EXISTS `shopdb_staff`              CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 医生药师管理（:8095）
CREATE DATABASE IF NOT EXISTS `shopdb_scheduling`         CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 排班（:8096）
CREATE DATABASE IF NOT EXISTS `shopdb_salary`             CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;  -- 工资结算（:8097）

GRANT ALL PRIVILEGES ON `shopdb_medicine`.*          TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_consult`.*           TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_agent`.*             TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_consult_record`.*    TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_prescription`.*      TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_prescription_mgmt`.* TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_doctor_assign`.*     TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_pharmacist_assign`.* TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_staff`.*             TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_scheduling`.*        TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_salary`.*            TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_medicine`.*          TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_consult`.*           TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_agent`.*             TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_consult_record`.*    TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_prescription`.*      TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_prescription_mgmt`.* TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_doctor_assign`.*     TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_pharmacist_assign`.* TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_staff`.*             TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_scheduling`.*        TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_salary`.*            TO 'shop'@'127.0.0.1';

FLUSH PRIVILEGES;
