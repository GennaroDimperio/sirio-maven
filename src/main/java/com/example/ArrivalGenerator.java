package com.example;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.Random;

/**
 * Genera una time-series di arrivi “a risacca” guidata dal workload:
 *   UP -> STABLE_UP -> DOWN -> STABLE_DOWN -> (loop)
 *
 * - Le variazioni del mix (up12/up23 e down21/down32) sono attive solo in UP/DOWN.
 * - I gettoni tra Rate1/Rate2/Rate3 (mixR1,mixR2,mixR3) si spostano di ±1, mantenendo la somma costante.
 * - Gli arrivi sono Poisson con lambda = (mixR1+mixR2+mixR3)/ARRIVAL_RATE_DIV.
 *
 * Output: "arrivals.csv" con colonne: t,cls
 */
public class ArrivalGenerator {

  // ===== Parametri principali =====
  static final double T = 1000.0; // durata simulazione (s)

  // Divisori (coerenti con il modello)
  static final double WORKLOAD_RATE_DIV   = 200.0; // 5/WD e 1/WD
  static final double MIX_STEP_RATE_DIV   = 10.0;  // 1/MIX_STEP_RATE_DIV per token
  static final int    ARRIVAL_RATE_DIV    = 10;    // lambda arrivi = sum/ARRIVAL_RATE_DIV

  // Stato iniziale del mix (esempio: 5-3-2)
  static int mixR1 = 5, mixR2 = 3, mixR3 = 2;
  static final int MIX_TOKENS_TOTAL = mixR1 + mixR2 + mixR3;

  // Seed per riproducibilità
  static final long SEED_ARRIVALS = 12345L;
  static final long SEED_MODEL    = 424242L;

  enum WorkloadState { UP, STABLE_UP, DOWN, STABLE_DOWN }

  public static void main(String[] args) throws Exception {
    try (PrintWriter out = new PrintWriter(new FileWriter("arrivals.csv"))) {
      out.println("t,cls");

      Random rngArrivals = new Random(SEED_ARRIVALS);
      Random rngModel    = new Random(SEED_MODEL);

      double now = 0.0;
      WorkloadState wl = WorkloadState.UP; // partiamo in UP

      // Arrivi Poisson omogenei (somma del mix è costante)
      final double arrivalLambda = (mixR1 + mixR2 + mixR3) / (double) ARRIVAL_RATE_DIV;
      double nextArrivalAt   = now + sampleExp(arrivalLambda, rngArrivals);

      // Workload: prossimo cambio di stato
      double nextWorkloadSwitchAt = now + sampleExp(workloadStateRate(wl), rngModel);

      // Mix variation: attiva solo in UP/DOWN
      double enabledMixRate  = mixEnabledRate(wl, mixR1, mixR2, mixR3);
      double nextMixChangeAt = (enabledMixRate > 0.0)
          ? now + sampleExp(enabledMixRate, rngModel)
          : Double.POSITIVE_INFINITY;

      while (now < T) {
        double nextEventAt = Math.min(nextArrivalAt, Math.min(nextWorkloadSwitchAt, nextMixChangeAt));
        if (nextEventAt > T) break;
        now = nextEventAt;

        if (nextEventAt == nextArrivalAt) {
          // Estrae classe proporzionale al mix corrente
          int cls = pickArrivalClass(mixR1, mixR2, mixR3, rngArrivals);
          out.printf(Locale.US, "%.6f,%d%n", now, cls);
          nextArrivalAt = now + sampleExp(arrivalLambda, rngArrivals);

        } else if (nextEventAt == nextWorkloadSwitchAt) {
          // Cambio di “modalità” del workload
          wl = nextWorkloadState(wl);
          nextWorkloadSwitchAt = now + sampleExp(workloadStateRate(wl), rngModel);

          // Accendi/spegni la possibilità di variare il mix
          enabledMixRate  = mixEnabledRate(wl, mixR1, mixR2, mixR3);
          nextMixChangeAt = (enabledMixRate > 0.0)
              ? now + sampleExp(enabledMixRate, rngModel)
              : Double.POSITIVE_INFINITY;

        } else {
          // Variazione del mix (abilitata solo in UP/DOWN)
          if (wl == WorkloadState.UP) {
            // up12 (r1->r2) e up23 (r2->r3)
            double rateUp12 = mixR1 * (1.0 / MIX_STEP_RATE_DIV);
            double rateUp23 = mixR2 * (1.0 / MIX_STEP_RATE_DIV);
            int choice = sampleWeightedIndex(new double[]{rateUp12, rateUp23}, rngModel);
            if (choice == 0 && mixR1 > 0) { mixR1--; mixR2++; }
            else if (choice == 1 && mixR2 > 0) { mixR2--; mixR3++; }

          } else if (wl == WorkloadState.DOWN) {
            // down21 (r2->r1) e down32 (r3->r2)
            double rateDown21 = mixR2 * (1.0 / MIX_STEP_RATE_DIV);
            double rateDown32 = mixR3 * (1.0 / MIX_STEP_RATE_DIV);
            int choice = sampleWeightedIndex(new double[]{rateDown21, rateDown32}, rngModel);
            if (choice == 0 && mixR2 > 0) { mixR2--; mixR1++; }
            else if (choice == 1 && mixR3 > 0) { mixR3--; mixR2++; }
          }

          // Conservazione della somma (sanity check)
          if (mixR1 + mixR2 + mixR3 != MIX_TOKENS_TOTAL)
            throw new IllegalStateException("Somma del mix non conservata!");

          // Ripianifica il prossimo evento di mix
          enabledMixRate  = mixEnabledRate(wl, mixR1, mixR2, mixR3);
          nextMixChangeAt = (enabledMixRate > 0.0)
              ? now + sampleExp(enabledMixRate, rngModel)
              : Double.POSITIVE_INFINITY;
        }
      }
    }

  System.out.println("[ok] arrivals.csv generato (T= " + T + "s)");
  }

  // Utility

  // Quanto spesso cambiamo “modalità” di workload
  static double workloadStateRate(WorkloadState s) {
    switch (s) {
      case UP:           return 5.0 / WORKLOAD_RATE_DIV;
      case STABLE_UP:    return 1.0 / WORKLOAD_RATE_DIV;
      case DOWN:         return 5.0 / WORKLOAD_RATE_DIV;
      case STABLE_DOWN:  return 1.0 / WORKLOAD_RATE_DIV;
      default: throw new IllegalArgumentException();
    }
  }

  static WorkloadState nextWorkloadState(WorkloadState s) {
    switch (s) {
      case UP:          return WorkloadState.STABLE_UP;
      case STABLE_UP:   return WorkloadState.DOWN;
      case DOWN:        return WorkloadState.STABLE_DOWN;
      case STABLE_DOWN: return WorkloadState.UP;
      default: throw new IllegalArgumentException();
    }
  }

  // Rate totale delle variazioni possibili sul mix nello stato attuale
  static double mixEnabledRate(WorkloadState s, int r1, int r2, int r3) {
    double perToken = 1.0 / MIX_STEP_RATE_DIV;
    if (s == WorkloadState.UP)   return r1 * perToken + r2 * perToken; // up12 + up23
    if (s == WorkloadState.DOWN) return r2 * perToken + r3 * perToken; // down21 + down32
    return 0.0; // in STABLE_* non si muove nulla
  }

  // Estrazione esponenziale con rate dato
  static double sampleExp(double rate, Random rng) {
    double u = Math.max(1e-12, 1.0 - rng.nextDouble()); // evita log(0)
    return -Math.log(u) / rate;
  }

  // Classe dell’arrivo in base al mix corrente
  static int pickArrivalClass(int r1, int r2, int r3, Random rng) {
    int sum = Math.max(1, r1 + r2 + r3);
    double u = rng.nextDouble();
    double p1 = r1 / (double) sum;
    double p2 = p1 + r2 / (double) sum;
    return (u < p1) ? 1 : (u < p2) ? 2 : 3;
  }

  // Estrae un indice proporzionalmente ai pesi (rate)
  static int sampleWeightedIndex(double[] weights, Random rng) {
    double sum = 0.0; for (double w : weights) sum += w;
    double u = rng.nextDouble() * sum;
    double acc = 0.0;
    for (int i = 0; i < weights.length; i++) {
      acc += weights[i];
      if (u <= acc) return i;
    }
    return weights.length - 1;
  }
}
