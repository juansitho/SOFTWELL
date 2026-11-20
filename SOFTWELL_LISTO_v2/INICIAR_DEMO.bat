@echo off
REM Arranca SOFTWELL y crea pedidos de demostracion (solo si aun no existen) para ver la prediccion.
cd /d "%~dp0softwell_project"
call mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--softwell.seed-demo=true --softwell.seed-demo.pedidos=3700 --softwell.seed-demo.dias=730"
pause
