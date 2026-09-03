@echo off
rem 仅重启问诊领域已实现的三个微服务（不打包、不打开浏览器）
cd /d D:\work\0802\0802-4
set JAVA_HOME=D:\jdk25\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

start "medicine-service" java -jar medicine-service\target\medicine-service-0.0.1-SNAPSHOT.jar
start "consult-service"  java -jar consult-service\target\consult-service-0.0.1-SNAPSHOT.jar
start "agent-service"    java -jar agent-service\target\agent-service-0.0.1-SNAPSHOT.jar

echo services: medicine 8081 / consult 8085+8086 / agent 8087
echo 占位服务（8090-8097）：consult-record / prescription / prescription-management / doctor-assignment / pharmacist-assignment / staff-management / scheduling / salary —— 实现要求见 harness\agents\
