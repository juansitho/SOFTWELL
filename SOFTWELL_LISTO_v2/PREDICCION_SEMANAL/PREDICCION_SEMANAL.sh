#!/bin/bash
cd "$(dirname "$0")"
export PYTHONUTF8=1
PY=python3; command -v python3 >/dev/null || PY=python
$PY predecir_demanda.py      # instala las librerias que falten la primera vez
