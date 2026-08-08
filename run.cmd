@echo off
rem ============================================================
rem  一键启动：启动 MySQL → 建库 → 构建 → 启动五个微服务
rem  用法：双击 run.cmd（首次会执行 Maven 打包，耗时约 1~2 分钟）
rem
rem  服务端口：
rem    product-service    http://localhost:8081
rem    order-service      http://localhost:8082  （浏览器入口）
rem    payment-service    http://localhost:8083
rem    recommend-service  http://localhost:8084  （AI 智能导购，无需数据库）
rem    consult-service    http://localhost:8085  REST / ws://localhost:8086  Netty WebSocket（在线问诊）
rem ============================================================
cd /d D:\work\0802\0802-4

rem ---------- 1. 钉死 JDK 25（本机默认 JAVA_HOME 是 JDK 1.8，无法编译/运行） ----------
set JAVA_HOME=D:\jdk25\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

rem ---------- 2. 启动 MySQL（如未运行） ----------
call D:\MySQL\start-mysql.cmd
echo.

rem ---------- 3. 检查数据库是否就绪（ddl-auto=update 只建表，不建库） ----------
rem   三个库需先用 root/DBeaver 执行一次 init.sql，否则无法启动
set MYSQL=D:\MySQL\mysql-8.4.5-winx64\bin\mysql.exe
"%MYSQL%" -ushop -pshop123456 -e "USE shopdb_product" 2>nul
if errorlevel 1 (
    echo [提示] 数据库 shopdb_product/order/payment 未就绪，请用 root 或 DBeaver 手动执行一次 init.sql：
    echo    mysql -uroot -p < init.sql
    echo    （本机 root 无密码，也可直接：mysql -uroot ^< init.sql）
    echo.
)

rem ---------- 4. 构建全部模块 ----------
if not exist product-service\target\product-service-0.0.1-SNAPSHOT.jar (
    echo 首次运行：正在 Maven 打包（mall-common → 五个服务）...
    call mvnw.cmd -DskipTests clean package
    if errorlevel 1 (
        echo [错误] 构建失败，请查看上方 Maven 日志。
        pause
        exit /b 1
    )
) else (
    echo 已存在构建产物，跳过打包。
)

rem ---------- 5. 启动五个服务（各自独立窗口） ----------
start "product-service"    java -jar product-service\target\product-service-0.0.1-SNAPSHOT.jar
start "order-service"      java -jar order-service\target\order-service-0.0.1-SNAPSHOT.jar
start "payment-service"    java -jar payment-service\target\payment-service-0.0.1-SNAPSHOT.jar
start "recommend-service"  java -jar recommend-service\target\recommend-service-0.0.1-SNAPSHOT.jar
start "consult-service"    java -jar consult-service\target\consult-service-0.0.1-SNAPSHOT.jar

rem ---------- 6. 等待服务就绪后打开浏览器 ----------
echo.
echo 正在启动五个服务，约 10 秒后自动打开浏览器（http://localhost:8082）...
timeout /t 10 /nobreak >nul
start http://localhost:8082
echo.
echo 服务地址：
echo   商品服务     http://localhost:8081/api/products
echo   订单服务     http://localhost:8082
echo   支付服务     http://localhost:8083/api/payments
echo   推荐服务     http://localhost:8084/api/recommendations （AI 智能导购）
echo   问诊服务     http://localhost:8085/api/consults （REST）/ ws://localhost:8086 （Netty WebSocket）
