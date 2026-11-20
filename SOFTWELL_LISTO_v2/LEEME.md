# SOFTWELL + modelo predictivo de demanda — listo para ejecutar

| Carpeta / archivo | Qué es |
|---|---|
| `EJECUTAR_PREDICCION.bat` | **Atajo: haz doble clic aquí** para ver la predicción semanal |
| `PREDICCION_SEMANAL/` | **Lo más fácil:** doble clic en `PREDICCION_SEMANAL.bat` y se abre el reporte semanal en el navegador. No necesita la app ni MongoDB. |
| `softwell_project/` | Tu proyecto completo con el modelo YA CONECTADO a `/admin/analitica` |
| `INICIAR_DEMO.bat` / `.sh` | Arranca SOFTWELL y crea pedidos de demostración (una sola vez) |
| `INICIAR.bat` / `.sh` | Arranque normal de SOFTWELL |
| `BORRAR_PEDIDOS_DEMO.bat` | Borra solo los pedidos demo (correo `@demo...`) |
| `EXPLICACION_MODELO.md` | Explicación del modelo para la exposición |

## Opción 1 — Predicción semanal sin la app (recomendada para la demo)
1. Ten instalado Python 3.10 o superior (python.org, marcando "Add python.exe to PATH").
2. Doble clic en `EJECUTAR_PREDICCION.bat`. Las librerías que falten se instalan solas la primera vez (necesita internet).
3. Se abre `resultados/prediccion_semanal.html` (también queda un CSV para Excel).
También sirve ejecutar `PREDICCION_SEMANAL/predecir_demanda.py` desde VS Code (botón Run) o la terminal: instala lo que falte
y corre. (Si ejecutas por error `softwell_demanda.py`, también lanza la predicción.)
Con tus ventas reales: ver `PREDICCION_SEMANAL/datos_reales/LEEME.txt`.

## Opción 2 — Dentro del panel de SOFTWELL
Requiere Java 21, internet y que la app conecte a tu MongoDB Atlas (en VS Code también sirve ejecutar `SoftwellApplication`).
1. Arranca la app: doble clic en `INICIAR.bat`.
2. Entra como ADMIN a http://localhost:8080/admin/analitica y baja hasta **"Predicción de demanda por producto"**.
3. La analítica utiliza todos los pedidos guardados y cuenta las cuentas cliente registradas. Si no hay ventas pagadas
   recientes, muestra un diagnóstico; no genera datos ficticios.
La predicción usa las últimas 8 semanas completas; las semanas sin ventas cuentan como 0 y, si el historial es corto,
lo avisa en pantalla. Con pocas ventas reales, la estimación será poco confiable.
Problemas de conexión con Atlas: timeout de DNS → cambia de red/DNS; `Connect timed out` al puerto 27017 → agrega tu IP en
Atlas (Network Access), revisa que el clúster no esté pausado y que tu red no bloquee ese puerto.

## Importante
* El panel de analítica calcula la predicción usando los pedidos pagados registrados en la base de datos.
* Los pedidos demo se guardan en tu MongoDB real (correo `@demo.softwell`) y se borran con `BORRAR_PEDIDOS_DEMO.bat`.
* Seguridad: `application.properties` y `env` tienen claves en texto (MongoDB, Gmail, Cloudinary). Cámbialas y usa
  variables de entorno antes de subir el proyecto a GitHub.
