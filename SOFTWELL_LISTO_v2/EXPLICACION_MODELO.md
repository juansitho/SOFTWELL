# Explicación del modelo predictivo de demanda (para la exposición)

## 1. Problema
Una joyería no sabe cuántas unidades de cada producto venderá la próxima semana: si compra de menos se queda sin
stock; si compra de más acumula inventario. El modelo estima la demanda semanal de cada producto y la compara con el stock.

## 2. Cómo aprende
Se le dan muchas filas de ejemplo (producto + semana) con la respuesta real (unidades vendidas) y busca patrones.
Variables usadas (las de la propuesta):
* Ventas recientes: unidades 1, 2, 3 y 4 semanas atrás y promedios móviles de 4 y 8 semanas.
* Época del año: semana y mes (San Valentín, Día de la Madre, Amor y Amistad, Navidad).
* Producto: categoría y precio.
* Actividad de la tienda: total vendido la semana anterior.
Solo cuentan pedidos en estado PAGADO. El stock y el proveedor NO se usan para entrenar porque la base solo guarda su
valor actual (no el historial); el stock se usa después, para la alerta.

## 3. Algoritmo
Random Forest: 300 árboles de decisión; la predicción es el promedio de todos. Se compararon cuatro algoritmos
(Ridge, Random Forest, Gradient Boosting y HistGradientBoosting) y se eligió por validación cruzada temporal.

## 4. Validación (sin trampa)
Se entrenó con el pasado y se probó en las últimas 26 semanas, que el modelo no vio.
| | MAE (unidades) | R² |
|---|---|---|
| Referencia: igual a la semana anterior | 2,16 | -0,08 |
| Referencia: promedio de 4 semanas | 1,71 | 0,32 |
| **Random Forest (elegido)** | **1,58** | **0,42** |
* Mejora ~8 % frente al promedio simple: modesta, y así se reporta.
* Se venden ~3 unidades por producto-semana; con cifras tan pequeñas el azar domina. El error mínimo alcanzable
  ronda 1,3, así que el modelo está cerca de ese piso.
* Error relativo: 54 % por producto-semana, 31 % por categoría-semana y 22 % por tienda-semana. Es más confiable para
  planear compras por categoría o en total que para un producto puntual.

## 5. Cómo se conecta
Python entrena y guarda el modelo (.joblib). Para el panel Java se exporta a JSON (300 árboles) y SOFTWELL lo evalúa sin
Python. Se verificó que Java y Python dan las mismas predicciones (16/16 productos, diferencia 0,0).
Alertas: REPONER si stock < demanda estimada; REVISAR si el stock supera 8 semanas de demanda.

## 6. Limitaciones (decirlas con honestidad)
* El modelo se entrenó con **datos simulados** (la propuesta ya lo contemplaba). Las métricas valen para esos datos, no
  para ventas reales. Con historial real se reentrena con `entrenar_modelo.py`.
* Usa el precio actual (no hay precio histórico) y supone que todos los productos existían todo el periodo.
* Predice una semana adelante y es solo apoyo: no compra ni modifica inventario.

## 7. Posibles preguntas
* ¿Por qué ese algoritmo? Se probaron cuatro y se eligió el de menor error en validación cruzada.
* ¿Por qué el error es alto? Ruido natural de ventas pequeñas; el modelo está cerca del mínimo posible.
* ¿Hay sobreajuste? No: se probó con semanas nunca vistas respetando el orden del tiempo.
* ¿Sirve con datos reales? La estructura sí; hay que reentrenarlo cuando exista historial real.
