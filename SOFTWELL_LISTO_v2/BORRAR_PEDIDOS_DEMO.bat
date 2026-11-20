@echo off
REM Borra SOLO los pedidos demo (correos @demo...). Despues cierra la app con Ctrl+C. Util para volver a generarlos.
cd /d "%~dp0softwell_project"
call mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--softwell.seed-demo.limpiar=true"
pause
