# Modelo predictivo de demanda – SOFTWELL

**Entregable principal: `modelo_demanda_softwell.joblib`** (modelo ya entrenado).

| | |
|---|---|
| Problema | Estimar la demanda de cada producto para apoyar la reposición de inventario |
| Variable objetivo | Unidades vendidas por producto en la **semana siguiente** (numérica) |
| Algoritmo elegido | Random Forest (regresión), elegido por validación cruzada temporal |
| Datos de entrenamiento | **Simulados** (2 años, 3.763 pedidos, 16 productos) con el formato exacto del export de SOFTWELL |
| Validación | Por tiempo: se entrena con el pasado y se prueba en las últimas 26 semanas |

## Variables predictoras (según la propuesta)
Unidades vendidas 1, 2, 3 y 4 semanas atrás · promedio móvil de 4 y 8 semanas · unidades totales de la tienda
la semana anterior · semana del año y mes (periodo de venta) · categoría · precio.
Solo se cuentan pedidos en estado **PAGADO**.

**Stock y proveedor no entran al entrenamiento:** la base solo guarda su valor actual, no su historial, y usarlos
metería información inconsistente. El stock se usa después, para la alerta de reposición.

## Resultados (semanas de prueba, no vistas al entrenar)
| Modelo | MAE | RMSE | R² |
|---|---|---|---|
| Referencia: igual a la semana anterior | 2,16 | 2,89 | -0,08 |
| Referencia: promedio últimas 4 semanas | 1,71 | 2,30 | 0,32 |
| Ridge | 1,64 | 2,21 | 0,37 |
| **Random Forest (elegido)** | **1,58** | **2,11** | **0,42** |
| Gradient Boosting | 1,57 | 2,12 | 0,42 |
| HistGradientBoosting (Poisson) | 1,51 | 2,04 | 0,46 |

* El promedio de ventas es 2,9 unidades por producto-semana. Con cifras tan pequeñas el azar domina: aun conociendo
  la tasa exacta de ventas, el error mínimo alcanzable ronda **1,3** unidades (piso de ruido). El modelo está cerca.
* Error relativo (WAPE): 54 % por producto-semana, **31 % por categoría-semana, 22 % por tienda-semana**.
  Es más confiable para planear compras por categoría o total que para un producto puntual en una semana.
* El modelo mejora ~8 % el MAE frente al promedio de 4 semanas; la mejora es modesta, y así se reporta.
* Gráficas y tablas en `resultados/`.

## Cómo usar el modelo ya entrenado
```
pip install -r requirements.txt
python predecir_demanda.py
```
Genera `resultados/predicciones_proxima_semana.csv/.json` con demanda estimada, stock actual, unidades a reponer y
alerta (`REPONER` si stock < demanda estimada; `REVISAR` si el stock supera 8 semanas de demanda).

Cargarlo en Python:
```python
import joblib
m = joblib.load("modelo_demanda_softwell.joblib")
m["pipeline"].predict(X)      # X con las columnas de m["features_num"] + m["features_cat"]
m["metadatos"]                # algoritmo, métricas, fechas, origen de los datos
```
Si tu versión de scikit-learn es distinta de la 1.8.0 y no abre el archivo, ejecuta `python entrenar_modelo.py`
(~1 minuto) y se regenera el `.joblib` en tu equipo.

## Reentrenar con datos REALES
1. Entra como ADMIN a `/admin/analitica/export.csv` y guarda `pedidos_softwell.csv`.
2. Exporta la colección `products` desde MongoDB Compass a `productos.csv` (columnas `nombre, categoria, precio, stock`).
3. Ejecuta:
```
python entrenar_modelo.py --pedidos pedidos_softwell.csv --productos productos.csv --origen real
python predecir_demanda.py --pedidos pedidos_softwell.csv --productos productos.csv
```
Hacen falta al menos ~65 semanas de historial; si hay menos, el script avisa.

## Conexión con el panel de SOFTWELL
El modelo está conectado a `/admin/analitica` (sección "Predicción de demanda por producto"). Como SOFTWELL es Java,
`exportar_modelo_java.py` convierte el `.joblib` en `modelo_demanda_softwell.json` (los 300 árboles) y el servidor
lo evalúa sin Python. Se verificó que Java y Python dan **las mismas predicciones** (16/16 productos, diferencia 0,0).
Los archivos a copiar al proyecto están en `softwell_conexion_panel.zip` (ver `INSTALAR.md`).
Al reentrenar con `entrenar_modelo.py` el JSON se regenera solo; cópialo a `src/main/resources/modelo/`.

## Archivos
| Archivo | Función |
|---|---|
| `modelo_demanda_softwell.joblib` | Modelo entrenado + metadatos |
| `entrenar_modelo.py` | Entrena, compara modelos, evalúa y guarda |
| `predecir_demanda.py` | Usa el modelo y genera las alertas |
| `softwell_demanda.py` | Lectura del CSV y construcción de variables |
| `exportar_modelo_java.py` | Exporta el modelo a JSON para el panel Java |
| `modelo_demanda_softwell.json` | Modelo en formato JSON (el que usa SOFTWELL) |
| `generar_datos_simulados.py` | Crea los datos de prueba |
| `datos/` | Pedidos y productos simulados |
| `resultados/` | Métricas, comparación de modelos, gráficas, predicciones |

## Limitaciones
* **Los datos son simulados**: replican la lógica de `DemoDataSeeder.java` (popularidad tipo Pareto, estacionalidad en
  febrero, mayo, septiembre y diciembre, crecimiento). Las métricas miden el desempeño sobre esos datos, no sobre ventas reales.
* Se usa el precio actual del producto (no hay precio histórico) y se asume que todos los productos existían todo el periodo.
* Los productos se leen del texto `descripcionItems`; si un producto cambia de nombre, esas ventas no se cruzan con el catálogo.
* Predice una semana adelante. Es apoyo para el administrador: no compra ni modifica inventario.
