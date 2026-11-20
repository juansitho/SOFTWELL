#!/usr/bin/env python3
"""
USA el modelo ya entrenado (modelo_demanda_softwell.joblib) para predecir las unidades que se
venderan de cada producto la SEMANA SIGUIENTE a la ultima semana completa de los datos, la compara
con el stock actual y genera:
    resultados/prediccion_semanal.html   (reporte que se abre en el navegador)
    resultados/predicciones_proxima_semana.csv / .json

Datos que usa (en este orden):
    1. datos_reales/pedidos_softwell.csv + datos_reales/productos.csv   (si existen)
    2. datos/pedidos_simulados.csv + datos/productos_simulados.csv       (de demostracion)

Si el .joblib no se puede abrir (otra version de scikit-learn, archivo faltante), se reentrena solo.
Apoyo para el administrador: no compra ni modifica inventario.
"""
import argparse
import base64
import html
import io
import json
import math
import os
import subprocess
import sys
import webbrowser

AQUI = os.path.dirname(os.path.abspath(__file__))
os.chdir(AQUI)

import _requisitos  # instala las librerias que falten
import joblib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sklearn

import softwell_demanda as sd

RUTA_MODELO = "modelo_demanda_softwell.joblib"


def cargar_modelo():
    """Abre el modelo; si no se puede (o es de otra version de scikit-learn) lo reentrena."""
    try:
        m = joblib.load(RUTA_MODELO)
        if m["metadatos"].get("scikit_learn") != sklearn.__version__:
            raise RuntimeError(f"modelo creado con scikit-learn {m['metadatos'].get('scikit_learn')}, "
                               f"instalado {sklearn.__version__}")
        return m
    except Exception as e:                                   # noqa: BLE001
        print(f"El modelo guardado no se pudo usar ({e}).\nReentrenando (≈1 minuto, una sola vez)...\n")
        r = subprocess.run([sys.executable, "entrenar_modelo.py"], cwd=AQUI)
        if r.returncode != 0:
            raise SystemExit("No se pudo reentrenar el modelo. Revisa los mensajes anteriores.")
        return joblib.load(RUTA_MODELO)


def png_base64(fig):
    b = io.BytesIO()
    fig.savefig(b, format="png", dpi=130, bbox_inches="tight")
    plt.close(fig)
    return base64.b64encode(b.getvalue()).decode()


def grafica_historial(df, semana_pred, total_pred):
    hist = df[df["semana"] < semana_pred].groupby("semana")["unidades"].sum().tail(12)
    fig, ax = plt.subplots(figsize=(9, 3.4))
    et = [d.strftime("%d/%m") for d in hist.index] + [semana_pred.strftime("%d/%m") + "\n(estimada)"]
    ax.bar(range(len(hist)), hist.values, color="#1565c0", label="Unidades vendidas")
    ax.bar(len(hist), total_pred, color="#c2185b", label="Estimación del modelo")
    ax.set_xticks(range(len(et))); ax.set_xticklabels(et, fontsize=8)
    ax.set_ylabel("Unidades por semana (toda la tienda)"); ax.legend(frameon=False)
    ax.spines[["top", "right"]].set_visible(False)
    return png_base64(fig)


def grafica_productos(out):
    o = out.sort_values("demanda_estimada")
    fig, ax = plt.subplots(figsize=(9, 0.38 * len(o) + 1.2))
    y = np.arange(len(o))
    ax.barh(y + 0.2, o["stock_actual"], height=0.38, color="#b0bec5", label="Stock actual")
    ax.barh(y - 0.2, o["demanda_estimada"], height=0.38, color="#c2185b", label="Demanda estimada")
    ax.set_yticks(y); ax.set_yticklabels(o["producto"], fontsize=8)
    ax.set_xlabel("Unidades"); ax.legend(frameon=False, loc="lower right")
    ax.spines[["top", "right"]].set_visible(False)
    return png_base64(fig)


COLOR = {"REPONER": "#c62828", "REVISAR": "#e65100", "OK": "#2e7d32"}


def reporte_html(out, meta, semana, df, info_datos, ruta):
    total = out["demanda_estimada"].sum()
    n_rep = int((out["alerta"].str.startswith("REPONER")).sum())
    n_exc = int((out["alerta"].str.startswith("REVISAR")).sum())
    simulado = meta["origen_datos"] == "simulado"
    filas = ""
    for r in out.itertuples():
        est = r.alerta.split(":")[0]
        filas += (f"<tr><td>{html.escape(r.producto)}</td><td>{html.escape(r.categoria)}</td>"
                  f"<td><b>{r.demanda_estimada:.1f}</b></td><td>{r.stock_actual}</td>"
                  f"<td>{r.unidades_a_reponer if r.unidades_a_reponer else '—'}</td>"
                  f"<td><span class='tag' style='background:{COLOR[est]}'>{est}</span> "
                  f"<small>{html.escape(r.alerta.split(': ', 1)[1]) if ': ' in r.alerta else 'Stock suficiente'}</small></td></tr>")
    m = meta["metricas_prueba"]; a = meta.get("precision_agregada", {})
    aviso_sim = ("<div class='aviso'>⚠ El modelo fue entrenado con <b>datos simulados</b>: es una demostración, "
                 "no describe ventas reales.</div>") if simulado else ""
    aviso_datos = f"<div class='aviso azul'>Datos usados para esta predicción: <b>{html.escape(info_datos)}</b></div>"
    doc = f"""<!DOCTYPE html><html lang="es"><head><meta charset="utf-8"><title>Predicción semanal SOFTWELL</title>
<style>body{{font-family:Segoe UI,Arial,sans-serif;margin:0;background:#f4f5f8;color:#222}}
.wrap{{max-width:1050px;margin:auto;padding:24px}} h1{{margin:0 0 4px}} .sub{{color:#666;margin-bottom:18px}}
.kpis{{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:14px;margin:16px 0}}
.kpi{{background:#fff;border-radius:12px;padding:14px 18px;box-shadow:0 2px 8px #0001}}
.kpi span{{display:block;color:#777;font-size:.8rem}} .kpi b{{font-size:1.7rem}}
.card{{background:#fff;border-radius:12px;padding:18px 22px;box-shadow:0 2px 8px #0001;margin:16px 0}}
.card h2{{font-size:1.05rem;margin:0 0 10px}} img{{max-width:100%}}
table{{border-collapse:collapse;width:100%}} th,td{{padding:8px 10px;border-bottom:1px solid #eee;text-align:left;font-size:.92rem}}
th{{background:#fafafa}} .tag{{color:#fff;padding:2px 10px;border-radius:12px;font-size:.75rem;font-weight:600}}
.aviso{{background:#fff8e1;border-left:4px solid #f9a825;border-radius:8px;padding:10px 16px;margin:10px 0;font-size:.9rem}}
.azul{{background:#eef5ff;border-color:#1565c0}} .pie{{color:#777;font-size:.8rem}}</style></head><body><div class="wrap">
<h1>Predicción de demanda semanal — SOFTWELL</h1>
<div class="sub">Semana del <b>{semana:%d/%m/%Y}</b> al <b>{(semana + pd.Timedelta(days=6)):%d/%m/%Y}</b> · modelo {html.escape(meta['algoritmo'])}</div>
{aviso_sim}{aviso_datos}
<div class="kpis"><div class="kpi"><span>Demanda total estimada</span><b>{total:.0f} u.</b></div>
<div class="kpi"><span>Productos a reponer</span><b style="color:{'#c62828' if n_rep else '#2e7d32'}">{n_rep}</b></div>
<div class="kpi"><span>Posible exceso de stock</span><b style="color:#e65100">{n_exc}</b></div>
<div class="kpi"><span>Productos analizados</span><b>{len(out)}</b></div></div>
<div class="card"><h2>Ventas semanales y estimación de la semana</h2><img src="data:image/png;base64,{grafica_historial(df, semana, total)}"></div>
<div class="card"><h2>Demanda estimada vs. stock actual por producto</h2><img src="data:image/png;base64,{grafica_productos(out)}"></div>
<div class="card"><h2>Detalle y alertas</h2><div style="overflow-x:auto"><table><thead><tr><th>Producto</th><th>Categoría</th>
<th>Demanda estimada</th><th>Stock actual</th><th>A reponer</th><th>Estado</th></tr></thead><tbody>{filas}</tbody></table></div>
<p class="pie"><b>REPONER</b>: el stock es menor que la demanda estimada. <b>REVISAR</b>: el stock supera 8 semanas de demanda.
Es un apoyo para el administrador: no compra ni modifica el inventario.</p></div>
<p class="pie">Error medio del modelo en semanas de prueba (MAE): {m['MAE']:.2f} unidades por producto-semana · R²: {m['R2']:.2f} ·
error relativo por categoría: {a.get('WAPE_categoria_semana_%', float('nan')):.0f}% · total tienda: {a.get('WAPE_tienda_semana_%', float('nan')):.0f}%.
Entrenado el {html.escape(meta['entrenado'])}.</p></div></body></html>"""
    with open(ruta, "w", encoding="utf-8") as f:
        f.write(doc)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pedidos", default=None)
    ap.add_argument("--productos", default=None)
    ap.add_argument("--fecha-corte", default=None)
    ap.add_argument("--semanas-exceso", type=float, default=8, help="stock > N semanas de demanda = exceso")
    ap.add_argument("--salida", default="resultados/predicciones_proxima_semana")
    ap.add_argument("--html", default="resultados/prediccion_semanal.html")
    ap.add_argument("--no-abrir", action="store_true", help="no abrir el reporte en el navegador")
    a = ap.parse_args()

    real = os.path.exists("datos_reales/pedidos_softwell.csv") and os.path.exists("datos_reales/productos.csv")
    pedidos = a.pedidos or ("datos_reales/pedidos_softwell.csv" if real else "datos/pedidos_simulados.csv")
    productos_csv = a.productos or ("datos_reales/productos.csv" if real else "datos/productos_simulados.csv")
    info_datos = "pedidos REALES de datos_reales/" if pedidos.startswith("datos_reales") else "pedidos SIMULADOS de demostración"

    m = cargar_modelo()
    meta = m["metadatos"]
    productos = sd.cargar_productos(productos_csv)
    lineas, _ = sd.cargar_lineas_vendidas(pedidos)
    tabla, info = sd.tabla_semanal(lineas, productos, a.fecha_corte, agregar_semana_futura=True)
    df = sd.construir_variables(tabla)
    futura = df[df["semana"] == df["semana"].max()].copy()
    if futura[sd.FEATURES_NUM].isna().any().any():
        raise SystemExit("Historial insuficiente: se necesitan al menos 8 semanas completas con ventas pagadas.")

    futura["demanda_estimada"] = np.clip(m["pipeline"].predict(futura[sd.FEATURES]), 0, None).round(1)
    futura["unidades_a_reponer"] = [max(0, math.ceil(d) - int(s)) for d, s in
                                    zip(futura["demanda_estimada"], futura["stock"])]

    def alerta(d, s):
        if s < d:
            return "REPONER: stock menor que la demanda estimada"
        if d > 0 and s > a.semanas_exceso * d:
            return "REVISAR: posible exceso de inventario"
        return "OK"
    futura["alerta"] = [alerta(d, s) for d, s in zip(futura["demanda_estimada"], futura["stock"])]
    semana = futura["semana"].iloc[0]
    futura["semana_inicio"] = semana.date()
    futura["semana_fin"] = (semana + pd.Timedelta(days=6)).date()

    out = futura.rename(columns={"nombre": "producto", "stock": "stock_actual"})[
        ["producto", "categoria", "semana_inicio", "semana_fin", "demanda_estimada",
         "stock_actual", "unidades_a_reponer", "alerta"]].sort_values("demanda_estimada", ascending=False)
    os.makedirs(os.path.dirname(a.salida) or ".", exist_ok=True)
    out.to_csv(a.salida + ".csv", index=False, encoding="utf-8-sig")
    json.dump({"modelo": meta["algoritmo"], "origen_datos": meta["origen_datos"],
               "semana_inicio": str(semana.date()),
               "predicciones": json.loads(out.to_json(orient="records", force_ascii=False))},
              open(a.salida + ".json", "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    reporte_html(out, meta, semana, df, info_datos, a.html)

    print(f"Modelo: {meta['algoritmo']} (entrenado con datos {meta['origen_datos']}) | datos usados: {info_datos}")
    print(f"Semana pronosticada: {semana.date()} a {(semana + pd.Timedelta(days=6)).date()}\n")
    print(out[["producto", "demanda_estimada", "stock_actual", "unidades_a_reponer", "alerta"]].to_string(index=False))
    print(f"\nDemanda total estimada de la tienda: {out['demanda_estimada'].sum():.0f} unidades")
    print(f"\nReporte: {os.path.abspath(a.html)}\nTabla:   {os.path.abspath(a.salida)}.csv")
    if meta["origen_datos"] == "simulado":
        print("AVISO: modelo entrenado con datos simulados; las cifras son una demostración, no ventas reales.")
    if not a.no_abrir:
        webbrowser.open("file:///" + os.path.abspath(a.html).replace("\\", "/"))


if __name__ == "__main__":
    main()
