#!/usr/bin/env python3
"""
Genera pedidos SIMULADOS de SOFTWELL con el MISMO formato del CSV que exporta
/admin/analitica/export.csv  (id,fecha,estado,cliente,email,total,iva,costo_envio,items)
y un catalogo de productos (nombre,categoria,precio,stock).

ATENCION: son datos de prueba academica, NO son ventas reales. Replican la logica de
DemoDataSeeder.java (popularidad tipo Pareto, productos que se compran juntos,
estacionalidad, 70% pagados) pero con mas volumen y 2 anios de historia para que el
modelo tenga con que aprender.

Uso:  python generar_datos_simulados.py
"""
import argparse
import csv
import os
from datetime import datetime, timedelta

import _requisitos  # instala las librerias que falten
import numpy as np

# (nombre, categoria, precio COP)
CATALOGO = [
    ("Anillo Blanco", "Anillos", 120000), ("Anillo Estrellas", "Anillos", 95000),
    ("Anillo Solitario Oro Blanco", "Anillos", 240000), ("Anillo Corazón Rosa", "Anillos", 110000),
    ("Aretes Blanco", "Aretes", 65000), ("Aretes Perla", "Aretes", 85000),
    ("Aretes Argolla Oro", "Aretes", 130000), ("Aretes Gota Cristal", "Aretes", 75000),
    ("Collar Brillante", "Collares", 150000), ("Collar Diamante", "Collares", 280000),
    ("Collar Corazón", "Collares", 120000), ("Collar Perla", "Collares", 175000),
    ("Pulsera Rosa", "Pulseras", 90000), ("Pulsera Charms", "Pulseras", 140000),
    ("Pulsera Tennis", "Pulseras", 210000), ("Pulsera Hilo Dorado", "Pulseras", 60000),
]

# Estacionalidad global por mes (ene..dic): San Valentin (feb), Dia de la Madre (may),
# Amor y Amistad (sep) y Navidad (dic)
ESTACION = [0.9, 1.4, 0.9, 0.9, 1.6, 1.0, 0.9, 0.9, 1.35, 1.0, 1.1, 1.9]
# Ajuste por categoria en meses especificos (mes -> multiplicador)
ESTACION_CAT = {
    "Anillos": {2: 1.3, 12: 1.1},
    "Collares": {5: 1.2, 12: 1.1},
    "Aretes": {5: 1.15},
    "Pulseras": {9: 1.25},
}
PESO_DIA = [0.9, 0.9, 0.9, 1.0, 1.3, 1.5, 1.2]  # lun..dom


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--fin", default="2026-11-01", help="ultimo dia (domingo) de la historia")
    ap.add_argument("--semanas", type=int, default=104)
    ap.add_argument("--pedidos-por-semana", type=float, default=26)
    ap.add_argument("--semilla", type=int, default=42)
    ap.add_argument("--salida", default="datos")
    a = ap.parse_args()

    rnd = np.random.default_rng(a.semilla)
    os.makedirs(a.salida, exist_ok=True)
    n = len(CATALOGO)

    # popularidad desigual (Pareto) repartida al azar entre categorias
    rango = rnd.permutation(n)
    peso_base = 1.0 / (rango + 1) ** 0.7
    companero = {i: int(rnd.choice([j for j in range(n) if j != i])) for i in range(n)}

    n_clientes = 150
    peso_cli = 1.0 / np.arange(1, n_clientes + 1) ** 0.9
    peso_cli /= peso_cli.sum()

    fin = datetime.fromisoformat(a.fin).replace(hour=23, minute=59)
    lunes0 = (fin - timedelta(days=6)).replace(hour=0, minute=0) - timedelta(weeks=a.semanas - 1)

    filas = []
    for w in range(a.semanas):
        lunes = lunes0 + timedelta(weeks=w)
        miercoles = lunes + timedelta(days=3)
        crecimiento = 1.0 + 0.5 * w / (a.semanas - 1)             # el negocio crece ~50% en 2 anios
        tasa = a.pedidos_por_semana * crecimiento * ESTACION[miercoles.month - 1]
        n_ped = rnd.poisson(tasa)
        if w == a.semanas - 1:
            n_ped = max(n_ped, 7)
        for k in range(n_ped):
            dia = int(rnd.choice(7, p=np.array(PESO_DIA) / sum(PESO_DIA)))
            if w == a.semanas - 1 and k == 0:
                dia = 6                                              # garantiza datos el ultimo domingo
            fecha = (lunes + timedelta(days=dia)).replace(hour=int(rnd.integers(8, 22)),
                                                          minute=int(rnd.integers(0, 60)),
                                                          second=int(rnd.integers(0, 60)))
            mes = fecha.month
            pesos = np.array([peso_base[i] * ESTACION_CAT.get(CATALOGO[i][1], {}).get(mes, 1.0)
                              for i in range(n)])
            pesos /= pesos.sum()
            ancla = int(rnd.choice(n, p=pesos))
            carrito = {ancla: 2 if rnd.random() < 0.15 else 1}
            if rnd.random() < 0.30:
                carrito[companero[ancla]] = carrito.get(companero[ancla], 0) + 1
            if rnd.random() < 0.12:
                j = int(rnd.choice(n, p=pesos))
                carrito[j] = carrito.get(j, 0) + 1

            subtotal = sum(CATALOGO[i][2] * q for i, q in carrito.items())
            items = ", ".join(f"{CATALOGO[i][0]} (x{q})" for i, q in carrito.items())
            r = rnd.random()
            estado = "PAGADO" if r < 0.72 else "PENDIENTE" if r < 0.89 else "RECHAZADO"
            c = int(rnd.choice(n_clientes, p=peso_cli)) + 1
            filas.append([
                f"{int(rnd.integers(0, 2**48)):012x}{int(rnd.integers(0, 2**48)):012x}",
                fecha.isoformat(), estado, f"Cliente Demo {c}", f"cliente{c}@demo.softwell",
                round(subtotal * 1.19 + 15000, 1), round(subtotal * 0.19, 1), 15000.0, items,
            ])

    filas.sort(key=lambda f: f[1])
    ruta_p = os.path.join(a.salida, "pedidos_simulados.csv")
    with open(ruta_p, "w", newline="", encoding="utf-8-sig") as f:   # BOM igual que el export de Java
        w = csv.writer(f)
        w.writerow(["id", "fecha", "estado", "cliente", "email", "total", "iva", "costo_envio", "items"])
        w.writerows(filas)

    # Catalogo con el stock ACTUAL (se usa solo para las alertas, no para entrenar)
    ruta_c = os.path.join(a.salida, "productos_simulados.csv")
    with open(ruta_c, "w", newline="", encoding="utf-8-sig") as f:
        w = csv.writer(f)
        w.writerow(["nombre", "categoria", "precio", "stock"])
        for nombre, cat, precio in CATALOGO:
            w.writerow([nombre, cat, precio, int(rnd.integers(4, 40))])

    print(f"{len(filas)} pedidos -> {ruta_p}")
    print(f"{n} productos -> {ruta_c}")


if __name__ == "__main__":
    main()
