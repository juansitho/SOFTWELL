#!/usr/bin/env python3
"""
Entrena, evalua y GUARDA el modelo predictivo de demanda de SOFTWELL.

Que predice:  unidades que se venderan de cada producto en la SEMANA SIGUIENTE.
Como valida:  por tiempo (entrena con el pasado, prueba con las ultimas N semanas),
              nunca mezcla semanas futuras en el entrenamiento.
Resultado:    modelo_demanda_softwell.joblib  + metricas y graficas en /resultados

Con datos simulados (por defecto):
    python entrenar_modelo.py
Con datos REALES (CSV de /admin/analitica/export.csv y la coleccion products exportada):
    python entrenar_modelo.py --pedidos pedidos_softwell.csv --productos productos.csv --origen real
"""
import argparse
import json
import os
import warnings
from datetime import datetime

import _requisitos  # instala las librerias que falten
import joblib
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import pandas as pd
import sklearn
from sklearn.compose import ColumnTransformer
from sklearn.ensemble import GradientBoostingRegressor, HistGradientBoostingRegressor, RandomForestRegressor
from sklearn.inspection import permutation_importance
from sklearn.linear_model import Ridge
from sklearn.metrics import mean_absolute_error, mean_squared_error, r2_score
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler

import softwell_demanda as sd

warnings.filterwarnings("ignore")


def metricas(y, p):
    p = np.clip(p, 0, None)
    return {"MAE": float(mean_absolute_error(y, p)),
            "RMSE": float(np.sqrt(mean_squared_error(y, p))),
            "R2": float(r2_score(y, p)),
            "WAPE_%": float(100 * np.abs(y - p).sum() / max(y.sum(), 1e-9))}


def pipeline(modelo, escalar=False):
    num = StandardScaler() if escalar else "passthrough"
    pre = ColumnTransformer([("num", num, sd.FEATURES_NUM),
                             ("cat", OneHotEncoder(handle_unknown="ignore"), sd.FEATURES_CAT)])
    return Pipeline([("pre", pre), ("modelo", modelo)])


def candidatos(semilla):
    c = {"Ridge (regresion lineal)": [(dict(alpha=a), pipeline(Ridge(alpha=a), True)) for a in (1.0, 10.0)]}
    c["Random Forest"] = [(dict(min_samples_leaf=l, max_features=f),
                           pipeline(RandomForestRegressor(n_estimators=300, min_samples_leaf=l, max_features=f,
                                                          random_state=semilla, n_jobs=-1)))
                          for l in (3, 8) for f in (0.6, 1.0)]
    c["Gradient Boosting"] = [(dict(max_depth=d, learning_rate=lr),
                               pipeline(GradientBoostingRegressor(n_estimators=250, max_depth=d, learning_rate=lr,
                                                                  subsample=0.8, random_state=semilla)))
                              for d in (2, 3) for lr in (0.03, 0.08)]
    c["HistGradientBoosting (Poisson)"] = [(dict(max_depth=d, max_iter=it),
                                            pipeline(HistGradientBoostingRegressor(
                                                loss="poisson", learning_rate=0.05, max_iter=it, max_depth=d,
                                                min_samples_leaf=20, l2_regularization=1.0, random_state=semilla)))
                                           for d in (3, None) for it in (150, 300)]
    return c


def pliegues_temporales(semanas, n=3, tam=13):
    """Validacion cruzada con ventana creciente: cada pliegue prueba en las `tam` semanas siguientes."""
    for i in range(n, 0, -1):
        ini = len(semanas) - i * tam
        yield semanas[:ini], semanas[ini:ini + tam]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--pedidos", default="datos/pedidos_simulados.csv")
    ap.add_argument("--productos", default="datos/productos_simulados.csv")
    ap.add_argument("--origen", default="simulado", choices=["simulado", "real"])
    ap.add_argument("--semanas-prueba", type=int, default=26)
    ap.add_argument("--fecha-corte", default=None, help="ultimo dia con datos completos (AAAA-MM-DD)")
    ap.add_argument("--semilla", type=int, default=42)
    ap.add_argument("--modelo-salida", default="modelo_demanda_softwell.joblib")
    ap.add_argument("--resultados", default="resultados")
    ap.add_argument("--json-salida", default="modelo_demanda_softwell.json",
                    help="version del modelo para SOFTWELL (Java); copiar a src/main/resources/modelo/")
    a = ap.parse_args()
    os.makedirs(a.resultados, exist_ok=True)

    # ---------------------------------------------------------------- datos
    productos = sd.cargar_productos(a.productos)
    lineas, info = sd.cargar_lineas_vendidas(a.pedidos)
    tabla, info2 = sd.tabla_semanal(lineas, productos, a.fecha_corte)
    df = sd.construir_variables(tabla).dropna(subset=sd.FEATURES_NUM + [sd.OBJETIVO]).reset_index(drop=True)
    semanas = np.sort(df["semana"].unique())
    print(f"Pedidos: {info['pedidos_total']} ({info['pedidos_pagados']} pagados) | "
          f"productos: {len(productos)} | semanas utiles: {len(semanas)} | filas: {len(df)}")
    if len(semanas) < a.semanas_prueba + 39:
        raise SystemExit(f"Historial insuficiente: hay {len(semanas)} semanas utiles y se necesitan al menos "
                         f"{a.semanas_prueba + 39}. Recolecta mas pedidos o usa datos simulados.")

    sem_ent, sem_pru = semanas[:-a.semanas_prueba], semanas[-a.semanas_prueba:]
    ent, pru = df[df.semana.isin(sem_ent)], df[df.semana.isin(sem_pru)]
    X_ent, y_ent, X_pru, y_pru = ent[sd.FEATURES], ent[sd.OBJETIVO].values, pru[sd.FEATURES], pru[sd.OBJETIVO].values

    # ----------------------------------------------- referencias (sin aprender)
    comparacion = [
        {"modelo": "Referencia: igual a la semana anterior", "tipo": "referencia", **metricas(y_pru, pru["lag_1"].values)},
        {"modelo": "Referencia: promedio ultimas 4 semanas", "tipo": "referencia", **metricas(y_pru, pru["media_4"].values)},
    ]

    # -------------------- seleccion de hiperparametros por validacion temporal
    print("\nValidacion cruzada temporal (solo con semanas de entrenamiento):")
    mejores = {}
    for familia, opciones in candidatos(a.semilla).items():
        res = []
        for params, pipe in opciones:
            maes = []
            for s_tr, s_va in pliegues_temporales(sem_ent):
                tr, va = ent[ent.semana.isin(s_tr)], ent[ent.semana.isin(s_va)]
                pipe.fit(tr[sd.FEATURES], tr[sd.OBJETIVO])
                maes.append(mean_absolute_error(va[sd.OBJETIVO], np.clip(pipe.predict(va[sd.FEATURES]), 0, None)))
            res.append((np.mean(maes), params, pipe))
        res.sort(key=lambda t: t[0])
        mejores[familia] = res[0]
        print(f"  {familia:34s} MAE_cv={res[0][0]:.3f}  {res[0][1]}")

    # ------------------------------------- evaluacion final en semanas no vistas
    pred_pru = {}
    for familia, (mae_cv, params, pipe) in mejores.items():
        pipe.fit(X_ent, y_ent)
        p = np.clip(pipe.predict(X_pru), 0, None)
        pred_pru[familia] = p
        comparacion.append({"modelo": familia, "tipo": "modelo", "MAE_validacion_cruzada": float(mae_cv),
                            "hiperparametros": json.dumps(params, default=str), **metricas(y_pru, p)})
    comp = pd.DataFrame(comparacion)
    modelos_df = comp[comp.tipo == "modelo"]
    ganador = modelos_df.sort_values("MAE_validacion_cruzada").iloc[0]["modelo"]   # elegido SIN mirar la prueba
    print("\nResultados en las ultimas", a.semanas_prueba, "semanas (no usadas para entrenar):")
    print(comp[["modelo", "MAE", "RMSE", "R2", "WAPE_%"]].round(3).to_string(index=False))
    print(f"\nModelo elegido (menor MAE en validacion cruzada): {ganador}")
    comp.to_csv(os.path.join(a.resultados, "comparacion_modelos.csv"), index=False, encoding="utf-8-sig")

    # --------- precision agregada y piso de ruido (con el modelo que NO vio la prueba)
    tmp = pru.assign(pred=pred_pru[ganador])
    wape = lambda d: float(100 * (d["unidades"] - d["pred"]).abs().sum() / max(d["unidades"].sum(), 1e-9))
    agregadas = {
        "WAPE_producto_semana_%": wape(tmp),
        "WAPE_categoria_semana_%": wape(tmp.groupby(["semana", "categoria"])[["unidades", "pred"]].sum().reset_index()),
        "WAPE_tienda_semana_%": wape(tmp.groupby("semana")[["unidades", "pred"]].sum().reset_index()),
        "media_unidades_producto_semana": float(tmp["unidades"].mean()),
    }
    # Piso de ruido aproximado: error esperado de una variable de conteo (Poisson) si se conociera su tasa
    # exacta; se toma como tasa la prediccion del modelo. Ningun modelo puede bajar mucho de este valor.
    from scipy.stats import poisson
    k = np.arange(0, 80)
    agregadas["piso_ruido_MAE_aprox"] = float(np.mean(
        [(poisson.pmf(k, max(l, 0.05)) * np.abs(k - max(l, 0.05))).sum() for l in tmp["pred"]]))
    print("Precision agregada y piso de ruido:", {k_: round(v, 2) for k_, v in agregadas.items()})

    # ------------------------------------------- reentrenar con TODO y guardar
    _, params_g, pipe_g = mejores[ganador]
    pipe_g.fit(pd.concat([X_ent, X_pru]), np.concatenate([y_ent, y_pru]))
    met_g = comp[comp.modelo == ganador].iloc[0]
    met_base = comp[comp.modelo.str.contains("4 semanas")].iloc[0]
    metadatos = {
        "nombre": "Modelo predictivo de demanda SOFTWELL", "version": "1.0",
        "entrenado": datetime.now().strftime("%Y-%m-%d %H:%M"),
        "algoritmo": ganador, "hiperparametros": params_g,
        "objetivo": "unidades vendidas por producto en la semana siguiente (lunes a domingo)",
        "origen_datos": a.origen, "semanas_entrenamiento": int(len(sem_ent)), "semanas_prueba": int(a.semanas_prueba),
        "filas_totales": int(len(df)), "productos": int(len(productos)),
        "periodo_datos": f"{info['fecha_min']} a {info['fecha_max']}",
        "scikit_learn": sklearn.__version__,
        "metricas_prueba": {k: round(float(met_g[k]), 4) for k in ("MAE", "RMSE", "R2", "WAPE_%")},
        "precision_agregada": {k_: round(v, 3) for k_, v in agregadas.items()},
        "metricas_referencia_media_4_semanas": {k: round(float(met_base[k]), 4) for k in ("MAE", "RMSE", "R2", "WAPE_%")},
        "nota": ("Entrenado con DATOS SIMULADOS: las metricas NO describen ventas reales."
                 if a.origen == "simulado" else "Entrenado con pedidos reales de SOFTWELL."),
    }
    joblib.dump({"pipeline": pipe_g, "features_num": sd.FEATURES_NUM, "features_cat": sd.FEATURES_CAT,
                 "categorias": sorted(productos["categoria"].unique().tolist()), "metadatos": metadatos},
                a.modelo_salida, compress=3)
    json.dump({"metadatos": metadatos, "comparacion": comp.round(4).to_dict("records"), **info, **info2},
              open(os.path.join(a.resultados, "metricas.json"), "w", encoding="utf-8"), indent=2, ensure_ascii=False)
    print(f"Modelo guardado en {a.modelo_salida} ({os.path.getsize(a.modelo_salida)/1024:.0f} KB)")
    try:
        import exportar_modelo_java
        exportar_modelo_java.exportar(a.modelo_salida, a.json_salida, verificar_con=df)
        print(f"-> Para actualizar el panel de SOFTWELL copia {a.json_salida} a src/main/resources/modelo/")
    except SystemExit as e:
        print("AVISO: no se genero el JSON para Java:", e)

    # ------------------------------------------------------------- graficas
    plt.rcParams.update({"font.size": 10, "axes.spines.top": False, "axes.spines.right": False})
    pg = pred_pru[ganador]
    pru = pru.assign(pred=pg, base=pru["media_4"].values)

    fig, ax = plt.subplots(figsize=(10, 4.2))
    t = pru.groupby("semana")[["unidades", "pred", "base"]].sum()
    ax.plot(t.index, t["unidades"], "o-", color="#222", lw=2, label="Ventas reales")
    ax.plot(t.index, t["pred"], "s-", color="#c2185b", lw=1.8, label=f"Prediccion del modelo")
    ax.plot(t.index, t["base"], "--", color="#888", lw=1.4, label="Referencia: promedio 4 semanas")
    ax.set(title="Unidades vendidas por semana en el periodo de prueba (todos los productos)",
           ylabel="Unidades por semana"); ax.legend(frameon=False); ax.grid(alpha=.25)
    fig.tight_layout(); fig.savefig(os.path.join(a.resultados, "1_real_vs_predicho_semanal.png"), dpi=150); plt.close(fig)

    fig, ax = plt.subplots(figsize=(5.2, 5))
    ax.scatter(pru["unidades"] + np.random.default_rng(0).uniform(-.12, .12, len(pru)), pru["pred"],
               s=14, alpha=.45, color="#c2185b")
    m = max(pru["unidades"].max(), pru["pred"].max()) + 1
    ax.plot([0, m], [0, m], "k--", lw=1); ax.set(xlabel="Ventas reales (unidades/semana)",
                                                  ylabel="Prediccion (unidades/semana)", title="Real vs. predicho por producto")
    fig.tight_layout(); fig.savefig(os.path.join(a.resultados, "2_dispersion_real_vs_predicho.png"), dpi=150); plt.close(fig)

    fig, ax = plt.subplots(figsize=(8, 4))
    o = comp.sort_values("MAE", ascending=False)
    ax.barh(o["modelo"], o["MAE"], color=["#999" if t == "referencia" else "#c2185b" for t in o["tipo"]])
    for i, v in enumerate(o["MAE"]): ax.text(v + .005, i, f"{v:.3f}", va="center")
    ax.set(title="Error absoluto medio (MAE) en semanas no vistas — menor es mejor", xlabel="Unidades por producto-semana")
    fig.tight_layout(); fig.savefig(os.path.join(a.resultados, "3_comparacion_modelos.png"), dpi=150); plt.close(fig)

    imp = permutation_importance(mejores[ganador][2], X_pru, y_pru, n_repeats=15, random_state=a.semilla,
                                 scoring="neg_mean_absolute_error")
    orden = np.argsort(imp.importances_mean)
    fig, ax = plt.subplots(figsize=(8, 4.5))
    ax.barh(np.array(sd.FEATURES)[orden], imp.importances_mean[orden], xerr=imp.importances_std[orden], color="#c2185b")
    ax.set(title="Importancia de variables (aumento del MAE al desordenarla)", xlabel="Aumento del MAE")
    fig.tight_layout(); fig.savefig(os.path.join(a.resultados, "4_importancia_variables.png"), dpi=150); plt.close(fig)

    top = pru.groupby("nombre")["unidades"].sum().nlargest(4).index
    fig, axs = plt.subplots(2, 2, figsize=(11, 6), sharex=True)
    for ax, nom in zip(axs.ravel(), top):
        s = pru[pru.nombre == nom]
        ax.plot(s["semana"], s["unidades"], "o-", color="#222", label="Real")
        ax.plot(s["semana"], s["pred"], "s-", color="#c2185b", label="Modelo")
        ax.set_title(nom, fontsize=10); ax.grid(alpha=.25)
    axs[0, 0].legend(frameon=False); fig.autofmt_xdate(); fig.tight_layout()
    fig.savefig(os.path.join(a.resultados, "5_top_productos.png"), dpi=150); plt.close(fig)
    print("Graficas y metricas guardadas en", a.resultados)


if __name__ == "__main__":
    main()
