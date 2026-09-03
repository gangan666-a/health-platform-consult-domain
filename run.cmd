@echo off
rem ============================================================
rem  医疗买药问诊平台 · 问诊领域：一键启动
rem  （MySQL → 建库 → 构建 → 启动已实现服务；占位服务无实现不启动）
rem
rem  已实现服务端口：
rem    medicine-service   http://localhost:8081  药品服务
rem    consult-service    http://localhost:8085  问诊会话/IM/开方（REST）
rem                       ws://localhost:8086    （WebSocket）
rem    agent-service      http://localhost:8087  多 Agent 智能体（项目主体）
rem
rem  问诊领域占位服务（实现要求见 harness/agents/，暂不启动）：
rem    consult-record-service              :8090  问诊记录
rem    prescription-service                :8091  开处方
rem    prescription-management-service     :8092  处方管理
rem    doctor-assignment-service           :8093  分配医生
rem    pharmacist-assignment-service       :8094  分配药师
rem    staff-management-service            :8095  医生药师管理
rem    scheduling-service                  :8096  排班服务
rem    salary-service                      :8097  工资结算
rem ============================================================
cd /d D:\work\0802\0802-4

set JAVA_HOME=D:\jdk25\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

call D:\MySQL\start-mysql.cmd
echo.

set MYSQL=D:\MySQL\mysql-8.4.5-winx64\bin\mysql.exe
"%MYSQL%" -ushop -pshop123456 -e "USE shopdb_medicine" 2>nul
if errorlevel 1 (
    echo [提示] 数据库未就绪，请用 root 执行一次 init.sql
    echo.
)

if not exist medicine-service\target\medicine-service-0.0.1-SNAPSHOT.jar (
    echo 首次运行：Maven 打包...
    call mvnw.cmd -DskipTests clean package
    if errorlevel 1 (
        echo [错误] 构建失败
        pause
        exit /b 1
    )
) else (
    echo 已存在构建产物，跳过打包。
)

start "medicine-service" java -jar medicine-service\target\medicine-service-0.0.1-SNAPSHOT.jar
start "consult-service"  java -jar consult-service\target\consult-service-0.0.1-SNAPSHOT.jar
start "agent-service"    java -jar agent-service\target\agent-service-0.0.1-SNAPSHOT.jar

echo.
echo 正在启动三个已实现服务，约 15 秒后打开浏览器...
timeout /t 15 /nobreak >nul
start http://localhost:8087/api/agents/registry
echo.
echo   Agent 服务     http://localhost:8087/api/agents/registry
echo   药品服务       http://localhost:8081/api/medicines
echo   问诊 REST/WS   http://localhost:8085 / ws://localhost:8086
