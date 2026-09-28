@echo off
rem Starts the backend with the dev (H2) profile
if not defined JAVA_HOME set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot"
cd /d "%~dp0"
call "%~dp0mvnw.cmd" spring-boot:run
