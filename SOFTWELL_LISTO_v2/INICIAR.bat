@echo off
REM Arranque normal de SOFTWELL (sin crear pedidos de demostracion).
cd /d "%~dp0softwell_project"
call mvnw.cmd spring-boot:run
pause
