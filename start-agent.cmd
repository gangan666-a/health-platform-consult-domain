@echo off
rem 单独启动 agent-service（钉死 JDK 25）
cd /d D:\work\0802\0802-4
set JAVA_HOME=D:\jdk25\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

echo Java:
java -version
echo.
echo [Agent] API Key 从 agent-service\src\main\resources\application.properties 读取
echo        配置项: agent.deepseek.api-key=sk-你的Key
echo.

java -jar agent-service\target\agent-service-0.0.1-SNAPSHOT.jar
