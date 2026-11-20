#!/usr/bin/env python3
"""
Convierte modelo_demanda_softwell.joblib en un JSON que SOFTWELL (Spring Boot) puede evaluar
en Java puro, sin Python en el servidor. Soporta Random Forest y Gradient Boosting.

    python exportar_modelo_java.py
Salida: modelo_demanda_softwell.json  (copiar a src/main/resources/modelo/)
Se ejecuta solo al final de entrenar_modelo.py.
"""
import argparse
import json

import _requisitos  # instala las librerias que falten
import joblib
import numpy as np
from sklearn.ensemble import GradientBoostingRegressor, RandomForestRegressor

import softwell_demanda as sd


def exportar(ruta_modelo="modelo_demanda_softwell.joblib", ruta_json="modelo_demanda_softwell.json", verificar_con=None):
    m = joblib.load(ruta_modelo)
    pipe = m["pipeline"]
    est = pipe.named_steps["modelo"]
    pre = pipe.named_steps["pre"]

    if isinstance(est, RandomForestRegressor):
        arboles, init, factor = list(est.estimators_), 0.0, 1.0 / len(est.estimators_)
    elif isinstance(est, GradientBoostingRegressor):
        arboles = [e[0] for e in est.estimators_]
        init, factor = float(est.init_.constant_.ravel()[0]), float(est.learning_rate)
    else:
        raise SystemExit(f"El conector Java soporta Random Forest y Gradient Boosting; el modelo es {type(est).__name__}.")

    # el orden de columnas que ve el arbol: numericas (tal cual) y luego one-hot de la categoria
    nombres = list(pre.get_feature_names_out())
    esperado = ["num__" + f for f in sd.FEATURES_NUM]
    assert nombres[:len(esperado)] == esperado, nombres
    categorias = [str(c) for c in pre.named_transformers_["cat"].categories_[0]]
    assert nombres[len(esperado):] == ["cat__categoria_" + c for c in categorias], nombres

    out = {
        "metadatos": m["metadatos"], "features_num": sd.FEATURES_NUM, "categorias": categorias,
        "init": init, "factor": factor, "arboles": [],
    }
    for e in arboles:
        t = e.tree_
        out["arboles"].append({
            "izq": t.children_left.tolist(), "der": t.children_right.tolist(),
            "feat": t.feature.tolist(), "umbral": [round(float(x), 9) for x in t.threshold],
            "valor": [round(float(v), 7) for v in t.value[:, 0, 0]],
        })
    json.dump(out, open(ruta_json, "w", encoding="utf-8"), ensure_ascii=False, separators=(",", ":"))

    # --- verificacion: reimplementar la evaluacion y comparar con scikit-learn ---
    if verificar_con is not None:
        Xt = pre.transform(verificar_con[sd.FEATURES])
        Xt = (Xt.toarray() if hasattr(Xt, "toarray") else np.asarray(Xt)).astype(np.float32)
        pred = np.zeros(len(Xt))
        for a in out["arboles"]:
            izq, der, feat, um, val = (np.array(a[k]) for k in ("izq", "der", "feat", "umbral", "valor"))
            nodo = np.zeros(len(Xt), dtype=int)
            while True:
                hoja = izq[nodo] == -1
                if hoja.all(): break
                f = feat[nodo]; f = np.where(hoja, 0, f)
                ir_izq = Xt[np.arange(len(Xt)), f] <= um[nodo]
                nodo = np.where(hoja, nodo, np.where(ir_izq, izq[nodo], der[nodo]))
            pred += val[nodo]
        pred = out["init"] + out["factor"] * pred
        dif = np.abs(pred - pipe.predict(verificar_con[sd.FEATURES])).max()
        print(f"Verificacion JSON vs scikit-learn: diferencia maxima = {dif:.2e}")
        assert dif < 1e-4, "El JSON exportado no reproduce al modelo"
    n = sum(len(a["izq"]) for a in out["arboles"])
    print(f"Exportado {ruta_json}: {len(out['arboles'])} arboles, {n} nodos")


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--modelo", default="modelo_demanda_softwell.joblib")
    ap.add_argument("--salida", default="modelo_demanda_softwell.json")
    a = ap.parse_args()
    import softwell_demanda as sd2
    p = sd2.cargar_productos("datos/productos_simulados.csv")
    l, _ = sd2.cargar_lineas_vendidas("datos/pedidos_simulados.csv")
    t, _ = sd2.tabla_semanal(l, p)
    df = sd2.construir_variables(t).dropna(subset=sd2.FEATURES_NUM + [sd2.OBJETIVO])
    exportar(a.modelo, a.salida, verificar_con=df)
