-- ============================================================
--  简易商城微服务版：初始化三个独立数据库 schema 并授权 shop 用户
--  用法（任选其一）：
--    方式一（root）： mysql -uroot -p < init.sql
--    方式二（DBeaver）：打开 shopdb 连接，执行本文件全部语句
--  说明：ddl-auto=update 只建表、不建库，因此需要先手动建库。
-- ============================================================

CREATE DATABASE IF NOT EXISTS `shopdb_product` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `shopdb_order`   CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS `shopdb_payment` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 授权给应用用户 shop（密码 shop123456），覆盖本机与 127.0.0.1 两种连接来源
GRANT ALL PRIVILEGES ON `shopdb_product`.* TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_order`.*   TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_payment`.* TO 'shop'@'localhost';
GRANT ALL PRIVILEGES ON `shopdb_product`.* TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_order`.*   TO 'shop'@'127.0.0.1';
GRANT ALL PRIVILEGES ON `shopdb_payment`.* TO 'shop'@'127.0.0.1';

FLUSH PRIVILEGES;
