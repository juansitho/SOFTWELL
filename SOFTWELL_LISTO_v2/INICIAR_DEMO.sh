#!/bin/bash
# Arranca SOFTWELL y crea pedidos de demostracion (solo si aun no existen).
cd "$(dirname "$0")/softwell_project" && chmod +x mvnw
./mvnw spring-boot:run -Dspring-boot.run.arguments="--softwell.seed-demo=true --softwell.seed-demo.pedidos=3700 --softwell.seed-demo.dias=730"
