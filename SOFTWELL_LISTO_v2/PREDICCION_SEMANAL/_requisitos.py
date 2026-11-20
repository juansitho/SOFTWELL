"""
Instala automaticamente las librerias que falten y vuelve a lanzar el script.
Cada script lo importa en su primera linea de codigo, asi funciona aunque lo ejecutes
con el boton "Run" de VS Code, desde la terminal o con el .bat, sin instalar nada a mano.
"""
import importlib.util
import os
import subprocess
import sys

MODULOS = ["numpy", "pandas", "sklearn", "scipy", "joblib", "matplotlib"]


def asegurar():
    faltan = [m for m in MODULOS if importlib.util.find_spec(m) is None]
    if not faltan:
        return
    aqui = os.path.dirname(os.path.abspath(__file__))
    print(f"Faltan librerias ({', '.join(faltan)}). Instalando automaticamente "
          f"(solo la primera vez, requiere internet; puede tardar unos minutos)...\n", flush=True)
    r = subprocess.call([sys.executable, "-m", "pip", "install", "-r", os.path.join(aqui, "requirements.txt")])
    if r != 0:
        raise SystemExit(
            "\nNo se pudieron instalar las librerias. Revisa tu conexion a internet y ejecuta manualmente:\n"
            f'  "{sys.executable}" -m pip install -r "{os.path.join(aqui, "requirements.txt")}"')
    print("\nLibrerias instaladas. Continuando...\n", flush=True)
    sys.exit(subprocess.call([sys.executable] + sys.argv))      # relanza el script original


asegurar()
