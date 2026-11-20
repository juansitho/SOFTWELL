package com.softwell.softwell.analytics;

import java.util.*;

/**
 * Algoritmos de minería de datos escritos en Java puro (sin librerías externas),
 * para que se puedan explicar y defender línea por línea en la sustentación.
 *
 *  1. Regresión lineal simple (pronóstico de ventas)
 *  2. K-Means (segmentación de clientes)
 *  3. Reglas de asociación tipo Apriori para pares (canasta de compra)
 */
public final class Mineria {

    private Mineria() {}

    // ------------------------------------------------------------------
    // 1. REGRESIÓN LINEAL SIMPLE  y = a*x + b   (mínimos cuadrados)
    // ------------------------------------------------------------------
    public record Regresion(double pendiente, double intercepto, double r2) {
        public double predecir(double x) { return pendiente * x + intercepto; }
    }

    /** x = 0,1,2,...,n-1 (periodos consecutivos). */
    public static Regresion regresionLineal(double[] y) {
        int n = y.length;
        if (n < 2) return new Regresion(0, n == 1 ? y[0] : 0, 0);

        double sx = 0, sy = 0, sxy = 0, sxx = 0;
        for (int i = 0; i < n; i++) {
            sx += i; sy += y[i]; sxy += i * y[i]; sxx += (double) i * i;
        }
        double den = n * sxx - sx * sx;
        double a = den == 0 ? 0 : (n * sxy - sx * sy) / den;
        double b = (sy - a * sx) / n;

        // R² = 1 - SSres / SStot
        double media = sy / n, ssRes = 0, ssTot = 0;
        for (int i = 0; i < n; i++) {
            double pred = a * i + b;
            ssRes += (y[i] - pred) * (y[i] - pred);
            ssTot += (y[i] - media) * (y[i] - media);
        }
        double r2 = ssTot == 0 ? 0 : 1 - ssRes / ssTot;
        return new Regresion(a, b, r2);
    }

    // ------------------------------------------------------------------
    // 2. K-MEANS
    // ------------------------------------------------------------------
    public record KMeansResultado(int[] asignacion, double[][] centroides, double inercia) {}

    /** Normalización min-max por columna → todas las variables quedan en [0,1]. */
    public static double[][] normalizar(double[][] datos) {
        if (datos.length == 0) return datos;
        int cols = datos[0].length;
        double[] min = new double[cols], max = new double[cols];
        Arrays.fill(min, Double.MAX_VALUE);
        Arrays.fill(max, -Double.MAX_VALUE);
        for (double[] fila : datos)
            for (int j = 0; j < cols; j++) {
                min[j] = Math.min(min[j], fila[j]);
                max[j] = Math.max(max[j], fila[j]);
            }
        double[][] out = new double[datos.length][cols];
        for (int i = 0; i < datos.length; i++)
            for (int j = 0; j < cols; j++) {
                double rango = max[j] - min[j];
                out[i][j] = rango == 0 ? 0 : (datos[i][j] - min[j]) / rango;
            }
        return out;
    }

    /**
     * K-Means determinista: el primer centroide es el punto más cercano a la media
     * y los siguientes se eligen por "farthest-first", así el resultado es
     * reproducible (siempre da lo mismo con los mismos datos).
     */
    public static KMeansResultado kmeans(double[][] datos, int k, int maxIter) {
        int n = datos.length;
        if (n == 0) return new KMeansResultado(new int[0], new double[0][0], 0);
        k = Math.max(1, Math.min(k, n));
        int dim = datos[0].length;

        // --- inicialización ---
        double[] media = new double[dim];
        for (double[] p : datos) for (int j = 0; j < dim; j++) media[j] += p[j] / n;

        double[][] cent = new double[k][];
        int primero = 0; double mejor = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            double d = dist2(datos[i], media);
            if (d < mejor) { mejor = d; primero = i; }
        }
        cent[0] = datos[primero].clone();
        for (int c = 1; c < k; c++) {
            int idx = 0; double lejano = -1;
            for (int i = 0; i < n; i++) {
                double dMin = Double.MAX_VALUE;
                for (int q = 0; q < c; q++) dMin = Math.min(dMin, dist2(datos[i], cent[q]));
                if (dMin > lejano) { lejano = dMin; idx = i; }
            }
            cent[c] = datos[idx].clone();
        }

        // --- iteraciones: asignar → recalcular ---
        int[] asig = new int[n];
        Arrays.fill(asig, -1);
        for (int it = 0; it < maxIter; it++) {
            boolean cambio = false;
            for (int i = 0; i < n; i++) {
                int mejorC = 0; double dMin = Double.MAX_VALUE;
                for (int c = 0; c < k; c++) {
                    double d = dist2(datos[i], cent[c]);
                    if (d < dMin) { dMin = d; mejorC = c; }
                }
                if (asig[i] != mejorC) { asig[i] = mejorC; cambio = true; }
            }
            if (!cambio) break;

            double[][] suma = new double[k][dim];
            int[] cuenta = new int[k];
            for (int i = 0; i < n; i++) {
                cuenta[asig[i]]++;
                for (int j = 0; j < dim; j++) suma[asig[i]][j] += datos[i][j];
            }
            for (int c = 0; c < k; c++)
                if (cuenta[c] > 0)
                    for (int j = 0; j < dim; j++) cent[c][j] = suma[c][j] / cuenta[c];
        }

        double inercia = 0;
        for (int i = 0; i < n; i++) inercia += dist2(datos[i], cent[asig[i]]);
        return new KMeansResultado(asig, cent, inercia);
    }

    private static double dist2(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) { double d = a[i] - b[i]; s += d * d; }
        return s;
    }

    // ------------------------------------------------------------------
    // 3. REGLAS DE ASOCIACIÓN (pares A → B)
    // ------------------------------------------------------------------
    public record Regla(String antecedente, String consecuente, int conteoPar,
                        double soporte, double confianza, double lift) {}

    /**
     * Cada transacción es el conjunto de productos distintos de un pedido.
     *  soporte(A,B)   = pedidos con A y B / total de pedidos
     *  confianza(A→B) = pedidos con A y B / pedidos con A
     *  lift(A→B)      = confianza / (pedidos con B / total)   (>1 = asociación real)
     */
    public static List<Regla> reglasAsociacion(List<Set<String>> transacciones,
                                               int minConteoPar, double minConfianza) {
        int total = transacciones.size();
        Map<String, Integer> item = new HashMap<>();
        Map<String, Integer> par = new HashMap<>();

        for (Set<String> t : transacciones) {
            List<String> lista = new ArrayList<>(new TreeSet<>(t));
            for (String a : lista) item.merge(a, 1, Integer::sum);
            for (int i = 0; i < lista.size(); i++)
                for (int j = i + 1; j < lista.size(); j++)
                    par.merge(lista.get(i) + "\u0000" + lista.get(j), 1, Integer::sum);
        }

        List<Regla> reglas = new ArrayList<>();
        for (Map.Entry<String, Integer> e : par.entrySet()) {
            if (e.getValue() < minConteoPar) continue;
            String[] ab = e.getKey().split("\u0000");
            double soporte = (double) e.getValue() / total;
            for (int dir = 0; dir < 2; dir++) {
                String a = ab[dir], b = ab[1 - dir];
                double conf = (double) e.getValue() / item.get(a);
                double lift = conf / ((double) item.get(b) / total);
                if (conf >= minConfianza)
                    reglas.add(new Regla(a, b, e.getValue(), soporte, conf, lift));
            }
        }
        reglas.sort(Comparator.comparingDouble(Regla::lift).reversed()
                .thenComparing(Comparator.comparingInt(Regla::conteoPar).reversed()));
        return reglas;
    }
}
