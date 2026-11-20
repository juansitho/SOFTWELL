# Módulo de Analítica (Visualización + Minería de Datos)

Ruta: **/admin/analitica** (solo ADMIN; aparece como "Analítica" en el menú lateral).
Descarga del dataset plano: **/admin/analitica/export.csv** (para Weka, Python, Power BI, Excel).

## Archivos nuevos
| Archivo | Qué hace |
|---|---|
| `analytics/Mineria.java` | Algoritmos en Java puro: regresión lineal, K-Means, reglas de asociación |
| `service/AnaliticaService.java` | Arma todos los datasets a partir de `pedidos` y `products` |
| `controller/AnaliticaController.java` | Página, JSON (`/datos`) y CSV (`/export.csv`) |
| `templates/admin-analitica.html` | Gráficas con Chart.js |

Modificados: solo se agregó el enlace "Analítica" al menú de 7 plantillas admin.
La analítica usa todos los pedidos guardados. El KPI de clientes cuenta las cuentas registradas con rol cliente.

## Visualización de datos
Ingresos mensuales + tendencia + pronóstico · estado de pedidos (dona) · pedidos por día de la
semana · ingresos por categoría (torta) · margen por categoría · Pareto/ABC de productos
(barras + línea acumulada) · burbujas de clientes (recencia × monto × frecuencia) · método del codo.
También se muestra el top 10 de productos por unidades compradas y el producto con mayor demanda semanal estimada.

## Minería de datos
1. **Regresión lineal** (mínimos cuadrados) sobre ingresos mensuales → pendiente, R² y pronóstico de 3 meses.
   El mes en curso se excluye del ajuste por estar incompleto.
2. **RFM + K-Means (k=3)**: normalización min-max, centroides iniciales deterministas, método del codo.
   Segmentos: VIP / Regulares / En riesgo.
3. **Reglas de asociación** (Apriori sobre pares): soporte, confianza y lift por pedido pagado.
4. **Pareto / Clasificación ABC** de productos por ingresos.

## Limitaciones conocidas
* Los productos de cada pedido se leen del texto `descripcionItems` ("Nombre (x2), ..."). Si cambias el
  nombre de un producto después de venderlo, esa venta se cuenta sin categoría ni precio.
* Los ingresos por producto/categoría usan el precio actual del producto (no el precio histórico).

## Predicción de demanda con modelo entrenado (Random Forest)
En `/admin/analitica`, al final de la página: **"Predicción de demanda por producto"**.
Estima las unidades que se venderán de cada producto en la semana en curso y las compara con el stock
(alertas REPONER / REVISAR / OK). Es solo apoyo para el administrador: no compra ni modifica inventario.

| Archivo | Qué hace |
|---|---|
| `resources/modelo/modelo_demanda_softwell.json` | Modelo YA ENTRENADO en Python (scikit-learn), exportado a JSON |
| `analytics/ModeloDemanda.java` | Carga el JSON y evalúa los 300 árboles (sin Python en el servidor) |
| `service/PrediccionDemandaService.java` | Arma las variables desde `pedidos` y `products` y llama al modelo |
| `controller/AnaliticaController.java` | Nuevo endpoint JSON `GET /admin/analitica/prediccion` |
| `templates/admin-analitica.html` | Nueva sección (tarjetas, gráfica y tabla) |

* Usa pedidos **PAGADO** de las últimas 8 semanas completas; las semanas sin ventas cuentan como 0. Con menos de 8 semanas
  con ventas muestra una advertencia, y si no hay ninguna venta en ese periodo presenta un diagnóstico.
* El modelo predictivo recibe sus variables de las ventas registradas en la base de datos.
