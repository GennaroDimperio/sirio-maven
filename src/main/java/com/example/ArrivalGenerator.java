package com.example;

import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Genera una time series di arrivi “a risacca”.
 * - La risacca modula nel tempo il MIX di classi (R1,R2,R3).
 * - Nei tratti a MIX costante, gli arrivi sono Poisson con
 *   lambda_tot = (R1+R2+R3) / RATE_DIV; la classe è estratta in base ai pesi R1,R2,R3.
 *
 * Output: crea "arrivals.csv" con colonne: t,cls
 */
public class ArrivalGenerator {

  // Parametri
  static final double T_END     = 300.0; // durata totale (s)
  static final int    CAP       = 8;     // intensità massima usata per R1/R3 nella risacca
  static final int    R2_CONST  = 1;     
  static final int    RATE_DIV  = 10;    // scala dell’intensità in arrivo

  // Seed
  static final long SEED_POISSON = 12345L;
  static final long SEED_RISACCA = 42L;

  static final class WP {
    final double t;
    final int r1, r2, r3;
    WP(double t, int r1, int r2, int r3) { this.t = t; this.r1 = r1; this.r2 = r2; this.r3 = r3; }
  }

  public static void main(String[] args) throws Exception {
    List<WP> traj = risacca(0.0, T_END, CAP, R2_CONST);

    try (PrintWriter out = new PrintWriter(new FileWriter("arrivals.csv"))) {
      out.println("t,cls");

      Random rng = new Random(SEED_POISSON);
      double t = 0.0;
      int seg = 0;

      while (t < T_END) {
        // Seleziona il segmento corrente
        while (seg + 1 < traj.size() && traj.get(seg + 1).t <= t) seg++;
        WP w = traj.get(seg);
        double segEnd = (seg + 1 < traj.size()) ? traj.get(seg + 1).t : T_END;

        // Tasso totale e pesi per classe nel segmento
        int tot = w.r1 + w.r2 + w.r3;
        double lambda = (tot > 0) ? (tot / (double) RATE_DIV) : 0.0;
        if (lambda <= 0.0) { t = segEnd; continue; }

        // Arrivi Poisson finché restiamo nel segmento
        while (t < segEnd) {
          // estrazione esponenziale
          double u = Math.max(1e-12, 1.0 - rng.nextDouble());
          double inter = -Math.log(u) / lambda;
          t += inter;
          if (t > segEnd || t > T_END) break;

          int cls = pickClass(w.r1, w.r2, w.r3, rng);
          out.printf(Locale.US, "%.6f,%d%n", t, cls);
        }
      }
    }

    System.out.println("[ok] arrivals.csv generato");
  }

  //Estrae la classe in base ai pesi (r1,r2,r3).
  static int pickClass(int r1, int r2, int r3, Random rng) {
    int sum = Math.max(1, r1 + r2 + r3);
    double u = rng.nextDouble();
    double p1 = r1 / (double) sum;
    double p2 = p1 + r2 / (double) sum;
    return (u < p1) ? 1 : (u < p2) ? 2 : 3;
  }

  /**
   * Generatore “a risacca” del profilo <R1,R2,R3>(t).
   * Alterna tratti “stable” (lunghi) a tratti di cambiamento (corti) in cui
   * i pesi si spostano ciclicamente tra R1 e R3.
   */
  static List<WP> risacca(double t0, double tEnd, int cap, int r2Const) {
    Random R = new Random(SEED_RISACCA);
    List<WP> seq = new ArrayList<>();
    double t = t0;
    int r1 = cap, r3 = 1; // inizio “lato R1”
    int dir = +1;         // +1 → sposta verso R3, -1 → torna verso R1

    while (t < tEnd) {
      boolean stable = R.nextDouble() < 0.5;
      double mean = stable ? 200.0 : 60.0; // durata media: lunghi se stabili, corti se in transizione
      double dur = -mean * Math.log(Math.max(1e-12, 1.0 - R.nextDouble()));
      dur = Math.min(dur, tEnd - t);

      if (!stable) {
        int delta = R.nextBoolean() ? 1 : 2;
        if (dir > 0) {
          int mv = Math.min(delta, r1 - 1);
          r1 -= mv; r3 += mv;
          if (r3 >= cap) dir = -1;
        } else {
          int mv = Math.min(delta, r3 - 1);
          r3 -= mv; r1 += mv;
          if (r1 >= cap) dir = +1;
        }
      }

      seq.add(new WP(t, r1, r2Const, r3));
      t += dur;
    }

    // chiusura: replica ultimo stato esattamente a tEnd
    if (seq.isEmpty() || seq.get(seq.size() - 1).t < tEnd) {
      WP last = seq.isEmpty() ? new WP(t0, cap, r2Const, 1) : seq.get(seq.size() - 1);
      seq.add(new WP(tEnd, last.r1, r2Const, last.r3));
    }
    return seq;
  }
}
