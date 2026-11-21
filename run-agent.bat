@echo off
rem Starts the Vyom sync agent. Run from this folder (reads .env and native\ here).
cd /d "%~dp0"
if not exist target\vyom-sync-agent.jar call mvnw.cmd -q -DskipTests package || exit /b 1
java -jar target\vyom-sync-agent.jar
