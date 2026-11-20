# Predicción semanal de demanda – SOFTWELL (sin app, sin MongoDB)

## Uso (Windows)
1. Instala **Python 3.10 o superior** (https://www.python.org/downloads/) marcando **"Add python.exe to PATH"**.
2. Doble clic en **`PREDICCION_SEMANAL.bat`**.
3. Se abre en el navegador el reporte **`resultados/prediccion_semanal.html`** con la demanda estimada de cada
   producto para la semana, el stock y las alertas (REPONER / REVISAR / OK).
   La tabla también queda en `resultados/predicciones_proxima_semana.csv` (se abre con Excel).

La primera vez **instala solo las librerías que falten** (necesita internet, puede tardar unos minutos); después tarda
pocos segundos. Funciona igual si ejecutas cualquier `.py` desde VS Code con el botón "Run" o desde la terminal
(por ejemplo `python predecir_demanda.py`). Si tu versión de scikit-learn es distinta de la del modelo guardado, lo
reentrena solo (~1 minuto, una vez). En Mac/Linux: `./PREDICCION_SEMANAL.sh`.

## ¿Con qué datos predice?
* Por defecto, con datos **simulados** de demostración (carpeta `datos/`): sirve para mostrar el modelo funcionando.
* Con tus ventas **reales**: sigue los pasos de `datos_reales/LEEME.txt` y vuelve a ejecutar el .bat.

## Qué hay aquí
| Archivo | Función |
|---|---|
| `PREDICCION_SEMANAL.bat` / `.sh` | Lanzador de un clic |
| `modelo_demanda_softwell.joblib` | Modelo ya entrenado (Random Forest) |
| `predecir_demanda.py` | **Usa el modelo y genera el reporte (el que debes ejecutar)** |
| `softwell_demanda.py`, `_requisitos.py` | Librerías auxiliares (si ejecutas `softwell_demanda.py` solo, lanza la predicción) |
| `entrenar_modelo.py` | Reentrena, compara modelos y genera métricas y gráficas |
| `resultados/` | Reporte, predicciones, métricas y gráficas |
| `README.md` | Explicación completa del modelo |

El modelo está entrenado con datos simulados; el reporte lo indica. Es un apoyo: no compra ni modifica inventario.
