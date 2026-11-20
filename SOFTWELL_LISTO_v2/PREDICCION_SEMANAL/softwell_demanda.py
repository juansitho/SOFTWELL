"""
Funciones compartidas por entrenar_modelo.py y predecir_demanda.py.

Convierte el CSV de pedidos que exporta SOFTWELL (/admin/analitica/export.csv) en una tabla
"producto x semana" con las variables predictoras de la propuesta:
  - unidades vendidas en semanas anteriores (lag_1..lag_4, medias moviles)
  - periodo de venta (semana del anio, mes)
  - categoria y precio del producto
  - estado del pedido: solo se cuentan los pedidos PAGADO como venta
El stock actual y el proveedor NO se usan para entrenar: la base solo guarda el valor actual
(no historico), asi que se usan unicamente para las alertas de reposicion.
"""
import re
import unicodedata

import _requisitos  # instala las librerias que falten
import numpy as np
import pandas as pd

# Mismo patron que AnaliticaService.java:  "Collar Brillante (x2), Anillo Blanco (x1)"
ITEM = re.compile(r"\s*(.+?) \(x(\d+)\)(?:,|$)")

FEATURES_NUM = ["lag_1", "lag_2", "lag_3", "lag_4", "media_4", "media_8",
                "unidades_tienda_lag1", "precio", "semana_sin", "semana_cos", "mes"]
FEATURES_CAT = ["categoria"]
FEATURES = FEATURES_NUM + FEATURES_CAT
OBJETIVO = "unidades"


def clave(texto: str) -> str:
    """Minusculas y sin tildes (igual que clave() en AnaliticaService.java)."""
    n = unicodedata.normalize("NFD", str(texto).strip())
    return "".join(c for c in n if unicodedata.category(c) != "Mn").lower()


def cargar_productos(ruta: str) -> pd.DataFrame:
    p = pd.read_csv(ruta, encoding="utf-8-sig")
    p.columns = [c.strip().lower() for c in p.columns]
    faltan = {"nombre", "categoria", "precio", "stock"} - set(p.columns)
    if faltan:
        raise ValueError(f"Al CSV de productos le faltan columnas: {sorted(faltan)}")
    p = p[p["nombre"].notna() & (p["precio"] > 0)].copy()
    p["clave"] = p["nombre"].map(clave)
    return p.drop_duplicates("clave").reset_index(drop=True)


def cargar_lineas_vendidas(ruta_pedidos: str):
    """Devuelve (lineas, info). lineas = una fila por producto vendido en pedidos PAGADO."""
    ped = pd.read_csv(ruta_pedidos, encoding="utf-8-sig")
    ped.columns = [c.strip().lower() for c in ped.columns]
    ped["fecha"] = pd.to_datetime(ped["fecha"], format="ISO8601", errors="coerce")
    total = len(ped)
    ped = ped[ped["fecha"].notna()]
    pagados = ped[ped["estado"].astype(str).str.upper() == "PAGADO"]
    filas = []
    for fecha, items in zip(pagados["fecha"], pagados["items"].fillna("")):
        for nombre, cant in ITEM.findall(items):
            filas.append((fecha, nombre.strip(), int(cant)))
    lineas = pd.DataFrame(filas, columns=["fecha", "producto", "cantidad"])
    info = {"pedidos_total": int(total), "pedidos_pagados": int(len(pagados)),
            "fecha_min": str(ped["fecha"].min().date()), "fecha_max": str(ped["fecha"].max().date())}
    return lineas, info


def tabla_semanal(lineas: pd.DataFrame, productos: pd.DataFrame,
                  fecha_corte=None, agregar_semana_futura=False):
    """
    Tabla producto x semana (lunes a domingo) con ceros en semanas sin ventas.
    Descarta la ultima semana si esta incompleta (su domingo es posterior a fecha_corte).
    Con agregar_semana_futura=True agrega la semana siguiente con 'unidades' = NaN
    (es la fila sobre la que se predice).
    """
    lineas = lineas.copy()
    lineas["clave"] = lineas["producto"].map(clave)
    desconocidas = lineas[~lineas["clave"].isin(productos["clave"])]
    lineas = lineas[lineas["clave"].isin(productos["clave"])]

    corte = pd.Timestamp(fecha_corte) if fecha_corte else lineas["fecha"].max()
    corte = corte.normalize()
    lineas["semana"] = lineas["fecha"].dt.normalize() - pd.to_timedelta(lineas["fecha"].dt.weekday, unit="D")
    ultimo_lunes = corte - pd.Timedelta(days=corte.weekday())
    if corte.weekday() != 6:                       # la semana en curso aun no termina
        ultimo_lunes -= pd.Timedelta(days=7)
    lineas = lineas[lineas["semana"] <= ultimo_lunes]

    semanas = pd.date_range(lineas["semana"].min(), ultimo_lunes, freq="7D")
    if agregar_semana_futura:
        semanas = semanas.append(pd.DatetimeIndex([ultimo_lunes + pd.Timedelta(days=7)]))

    vendidas = lineas.groupby(["clave", "semana"])["cantidad"].sum()
    idx = pd.MultiIndex.from_product([productos["clave"], semanas], names=["clave", "semana"])
    g = vendidas.reindex(idx).reset_index(name=OBJETIVO)
    if agregar_semana_futura:
        g.loc[g["semana"] == semanas[-1], OBJETIVO] = np.nan
        g.loc[g["semana"] != semanas[-1], OBJETIVO] = g.loc[g["semana"] != semanas[-1], OBJETIVO].fillna(0)
    else:
        g[OBJETIVO] = g[OBJETIVO].fillna(0)
    g = g.merge(productos[["clave", "nombre", "categoria", "precio", "stock"]], on="clave")
    return g, {"unidades_sin_catalogo": int(desconocidas["cantidad"].sum()),
               "productos_sin_catalogo": sorted(desconocidas["producto"].unique().tolist()),
               "ultima_semana_completa": str(ultimo_lunes.date())}


def construir_variables(g: pd.DataFrame) -> pd.DataFrame:
    """Variables predictoras: cada fila usa SOLO informacion de semanas anteriores."""
    g = g.sort_values(["clave", "semana"]).reset_index(drop=True)
    por_prod = g.groupby("clave")[OBJETIVO]
    for k in range(1, 5):
        g[f"lag_{k}"] = por_prod.shift(k)
    g["media_4"] = g[["lag_1", "lag_2", "lag_3", "lag_4"]].mean(axis=1, skipna=False)
    g["media_8"] = por_prod.transform(lambda s: s.shift(1).rolling(8).mean())
    tienda = g.groupby("semana")[OBJETIVO].sum(min_count=1).shift(1)
    g["unidades_tienda_lag1"] = g["semana"].map(tienda)
    semana_anio = g["semana"].dt.isocalendar().week.astype(int)
    g["semana_sin"] = np.sin(2 * np.pi * semana_anio / 52.18)
    g["semana_cos"] = np.cos(2 * np.pi * semana_anio / 52.18)
    g["mes"] = (g["semana"] + pd.Timedelta(days=3)).dt.month
    return g


if __name__ == "__main__":
    # Este archivo es una libreria auxiliar. Si se ejecuta directamente, se corre la prediccion semanal.
    print("softwell_demanda.py es una libreria auxiliar; ejecutando predecir_demanda.py...\n")
    import predecir_demanda
    predecir_demanda.main()
