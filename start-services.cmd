@echo off
rem restart 5 microservices only (no build, no browser)
cd /d D:\work\0802\0802-4
set JAVA_HOME=D:\jdk25\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%

start "product-service"    java -jar product-service\target\product-service-0.0.1-SNAPSHOT.jar
start "payment-service"    java -jar payment-service\target\payment-service-0.0.1-SNAPSHOT.jar
start "recommend-service"  java -jar recommend-service\target\recommend-service-0.0.1-SNAPSHOT.jar
start "order-service"      java -jar order-service\target\order-service-0.0.1-SNAPSHOT.jar
start "consult-service"    java -jar consult-service\target\consult-service-0.0.1-SNAPSHOT.jar

echo services started: product 8081 / order 8082 / payment 8083 / recommend 8084 / consult 8085(ws 8086)
