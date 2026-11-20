package com.softwell.softwell.analytics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modelo predictivo de demanda ya ENTRENADO (Python / scikit-learn) y exportado a JSON
 * (archivo resources/modelo/modelo_demanda_softwell.json).
 *
 * Aquí solo se EVALÚA: es un conjunto de árboles de decisión (Random Forest o Gradient Boosting).
 * No se necesita Python en el servidor. Para reentrenarlo con pedidos reales se usa
 * entrenar_modelo.py, que vuelve a generar el JSON.
 *
 * Predicción = init + factor * (suma del valor de la hoja alcanzada en cada árbol).
 */
public final class ModeloDemanda {

    public static final String RECURSO = "/modelo/modelo_demanda_softwell.json";

    private final List<String> featuresNum;
    private final List<String> categorias;
    private final double init;
    private final double factor;
    private final int[][] izq, der, feat;
    private final double[][] umbral, valor;
    private final Map<String, Object> metadatos;

    private ModeloDemanda(JsonNode raiz, ObjectMapper om) {
        featuresNum = new ArrayList<>();
        raiz.get("features_num").forEach(n -> featuresNum.add(n.asText()));
        categorias = new ArrayList<>();
        raiz.get("categorias").forEach(n -> categorias.add(n.asText()));
        init = raiz.get("init").asDouble();
        factor = raiz.get("factor").asDouble();
        metadatos = om.convertValue(raiz.get("metadatos"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});

        JsonNode arboles = raiz.get("arboles");
        int n = arboles.size();
        izq = new int[n][]; der = new int[n][]; feat = new int[n][];
        umbral = new double[n][]; valor = new double[n][];
        for (int i = 0; i < n; i++) {
            JsonNode a = arboles.get(i);
            izq[i] = enteros(a.get("izq")); der[i] = enteros(a.get("der")); feat[i] = enteros(a.get("feat"));
            umbral[i] = decimales(a.get("umbral")); valor[i] = decimales(a.get("valor"));
        }
    }

    public static ModeloDemanda cargarDesdeRecurso() throws IOException {
        try (InputStream in = ModeloDemanda.class.getResourceAsStream(RECURSO)) {
            if (in == null) throw new IOException("No se encontró " + RECURSO + " en el classpath");
            return cargar(in);
        }
    }

    public static ModeloDemanda cargar(InputStream in) throws IOException {
        ObjectMapper om = new ObjectMapper();
        return new ModeloDemanda(om.readTree(in), om);
    }

    /** Estima las unidades de la semana. {@code num} trae las variables numéricas por nombre. */
    public double predecir(Map<String, Double> num, String categoria) {
        int nn = featuresNum.size();
        float[] x = new float[nn + categorias.size()];     // float: igual que scikit-learn
        for (int i = 0; i < nn; i++) x[i] = num.get(featuresNum.get(i)).floatValue();
        int c = indiceCategoria(categoria);
        if (c >= 0) x[nn + c] = 1f;                          // categoría desconocida = todo en 0

        double suma = 0;
        for (int t = 0; t < izq.length; t++) {
            int nodo = 0;
            while (izq[t][nodo] != -1) nodo = (x[feat[t][nodo]] <= umbral[t][nodo]) ? izq[t][nodo] : der[t][nodo];
            suma += valor[t][nodo];
        }
        return Math.max(0, init + factor * suma);
    }

    private int indiceCategoria(String categoria) {
        if (categoria == null) return -1;
        int i = categorias.indexOf(categoria);
        if (i >= 0) return i;
        String k = clave(categoria);
        for (int j = 0; j < categorias.size(); j++) if (clave(categorias.get(j)).equals(k)) return j;
        return -1;
    }

    public static String clave(String s) {
        return Normalizer.normalize(s.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }

    public Map<String, Object> metadatos() { return metadatos; }
    public List<String> categorias() { return categorias; }
    public int numeroArboles() { return izq.length; }

    private static int[] enteros(JsonNode a) { int[] r = new int[a.size()]; for (int i = 0; i < r.length; i++) r[i] = a.get(i).asInt(); return r; }
    private static double[] decimales(JsonNode a) { double[] r = new double[a.size()]; for (int i = 0; i < r.length; i++) r[i] = a.get(i).asDouble(); return r; }
}
