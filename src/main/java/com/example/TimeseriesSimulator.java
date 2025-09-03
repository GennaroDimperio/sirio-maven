package com.example;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * TimeseriesSimulator
 *
 * Pipeline:
 *  1) legge la timeseries dal file arrivals.csv (t,cls)
 *  2) disabilita gli arrivi automatici dal modello GSPN
 *  3) ogni T=10s:
 *      - stima i rate sugli ultimi W=20s
 *      - calcola il totale target di repliche (pool+busy) per i prossimi 10s
 *        scegliendo il MINIMO valore tale che la rejection prevista ≤ SLO
 *      - applica subito i token in Pool per ottenere quel totale
 *  4) integra l’idle e conta le rejection reali
 *
 * Output:
 *  - stdout con metriche globali
 *  - CSV: timeseries_sli.csv (riassunto) e timeseries_intervals.csv (per intervalli)
 */
public class TimeseriesSimulator {

  // ===== Parametri controller / stima =====
  static final double W_WINDOW_SEC   = 20.0;  // ampiezza finestra per stima dei rate
  static final double ADAPT_STEP_SEC = 10.0;  // periodo dell’adattatore T
  static final double PRED_HORIZ_SEC = 10.0;  // orizzonte di predizione
  static final double SLO            = 0.01;  // target di rejection (max)

  // ===== Limiti risorse =====
  static final int POOL_MIN = 1;
  static final int POOL_MAX = 24;

  // ===== Logging =====
  static final int LOG_EVERY = 50;

  // ===== Diagnostica predizione =====
  static double LAST_PRED_REJ_RATE = Double.NaN;
  static int    LAST_PRED_N        = 0;

  // ===== Modello GSPN =====
  static class Arrival {
    final double t; final int cls;
    Arrival(double t, int cls){ this.t = t; this.cls = cls; }
  }

  // Cache di BphDiv per i tempi medi “statici” nello snapshot
  static Integer CACHED_BPH = null;

  public static void main(String[] args) throws Exception {
    final String arrivalsPath = (args != null && args.length > 0) ? args[0] : "arrivals.csv";

    ModelOris2_fase4.GspnModel m = ModelOris2_fase4.build();
    disableAutomaticArrivals(m);

    List<Arrival> arr = readArrivals(arrivalsPath);
    System.out.println("[info] arrivals file: " + arrivalsPath + "  |  letti " + arr.size() + " arrivi");
    if (arr.isEmpty()) return;

    SlidingRateEstimator est = new SlidingRateEstimator(W_WINDOW_SEC, ADAPT_STEP_SEC);
    Random rng = new Random(777);

    // Metriche globali
    double totalTime = 0.0;
    double idleIntegral = 0.0;
    int rejections = 0;

    // Metriche per intervallo di adattamento
    double intervalStart = arr.get(0).t;
    int arrivalsInInterval = 0;
    int rejectsInInterval  = 0;
    double idleIntInterval = 0.0;
    int    lastChosenTot      = -1;
    double lastEffectiveChange= -1.0;

    try (PrintWriter csvInt = new PrintWriter(new FileWriter("timeseries_intervals.csv"));
         PrintWriter csvSum = new PrintWriter(new FileWriter("timeseries_sli.csv"))) {

      csvInt.println("t_start,t_end,pool_now,target_tot,eff_change_time,arrivals,rejections,rejection_rate,idle_mean_interval,pred_rej_at_target,pred_n");
      csvSum.println("total_time_s,rejections,rejection_rate,idle_mean");

      double simClock = arr.get(0).t;
      double nextCtrl = roundUpToGrid(simClock, ADAPT_STEP_SEC);

      for (int i = 0; i < arr.size(); i++) {
        Arrival a = arr.get(i);

        // Il controller può scattare più volte prima del prossimo arrivo
        while (nextCtrl <= a.t) {
          // Avanza dinamica fino a nextCtrl e integra l'idle
          Delta d = advanceAndIntegrate(m, rng, simClock, nextCtrl);
          idleIntegral    += d.idle;
          idleIntInterval += d.idle;
          totalTime       += d.dt;
          simClock         = nextCtrl;

          // Stimo i rate sugli ultimi W (qui NON usati dal baseline,
          // ma utili per diagnostica/estensioni del controller)
          SlidingRateEstimator.Rates r = est.estimateRatesAt(nextCtrl);
          // System.out.println("[diag] rates @"+nextCtrl+"s = "+r);

          // Calcolo il totale minimo di repliche che rispetta lo SLO nel prossimo orizzonte
          int chosenTotReplicas = chooseTotalReplicasMinSLO(
              m, nextCtrl, PRED_HORIZ_SEC, arr, i, rng
          );

          // Applico: pool = clamp( chosenTot - busy, [POOL_MIN, POOL_MAX] )
          int busyNow  = busyCount(m);
          int needPool = Math.max(0, chosenTotReplicas - busyNow);
          set(m, "Pool", Math.max(POOL_MIN, Math.min(POOL_MAX, needPool)));

          lastChosenTot       = chosenTotReplicas;
          lastEffectiveChange = nextCtrl;

          // Log intervallo chiuso all’istante nextCtrl
          int poolNow = get(m,"Pool");
          writeIntervalIfClosed(csvInt, intervalStart, nextCtrl,
              poolNow, lastChosenTot, lastEffectiveChange,
              arrivalsInInterval, rejectsInInterval, idleIntInterval,
              LAST_PRED_REJ_RATE, LAST_PRED_N);

          // Reset intervallo
          intervalStart       = nextCtrl;
          arrivalsInInterval  = 0;
          rejectsInInterval   = 0;
          idleIntInterval     = 0.0;

          nextCtrl += ADAPT_STEP_SEC;
        }

        // Registra arrivo per la stima (finestra mobile)
        est.add(a.t, a.cls);

        // Avanza fino all'arrivo e integra l'idle
        Delta d = advanceAndIntegrate(m, rng, simClock, a.t);
        idleIntegral    += d.idle;
        idleIntInterval += d.idle;
        totalTime       += d.dt;
        simClock         = a.t;

        // Inject dell'arrivo (probability switch manuale). Rejection se Pool==0
        boolean ok = injectArrival(m, a.cls, rng);
        if (!ok) { rejections++; rejectsInInterval++; }
        arrivalsInInterval++;

        if ((i+1) % LOG_EVERY == 0) {
          System.out.printf(Locale.US,
              "[progress] %d/%d  t=%.3f  rej=%d  Pool=%d  busy=%d%n",
              (i+1), arr.size(), a.t, rejections, get(m,"Pool"), busyCount(m));
        }
      }

      // Chiusura ultimo intervallo a fine serie
      double lastT = arr.get(arr.size()-1).t;
      int poolNowEnd = get(m, "Pool");
      writeIntervalIfClosed(csvInt, intervalStart, lastT,
          poolNowEnd, lastChosenTot, lastEffectiveChange,
          arrivalsInInterval, rejectsInInterval, idleIntInterval,
          LAST_PRED_REJ_RATE, LAST_PRED_N);

      // Metriche complessive
      double idleMean = (totalTime > 0) ? idleIntegral / totalTime : 0.0;
      double rejRate  = !arr.isEmpty()  ? (double) rejections / arr.size() : 0.0;

      System.out.println();
      System.out.println("== RISULTATI TIMESERIES (baseline) ==");
      System.out.println("Tempo totale simulato: " + String.format(Locale.US,"%.3f", totalTime) + " s");
      System.out.println("Rejection totali:      " + rejections);
      System.out.println("Rejection rate:        " + String.format(Locale.US,"%.6f", rejRate));
      System.out.println("Idle medio (Pool):     " + String.format(Locale.US,"%.3f", idleMean));

      csvSum.printf(Locale.US, "%.3f,%d,%.6f,%.3f%n", totalTime, rejections, rejRate, idleMean);
      System.out.println("CSV scritto: timeseries_sli.csv");
      System.out.println("CSV per intervalli: timeseries_intervals.csv");
    }
  }

  // ----------------- Controller (baseline) -----------------

  /**
   * Ritorna il MINIMO totale di repliche (pool+busy) tale che,
   * simulando i prossimi 'horizon' secondi con totale costante (nessun ritardo),
   * la rejection prevista ≤ SLO.
   */
  static int chooseTotalReplicasMinSLO(
      ModelOris2_fase4.GspnModel m,
      double now,
      double horizon,
      List<Arrival> arr,
      int currentIdx,
      Random rng
  ){
    // Stato attuale
    int poolNow = get(m,"Pool");
    int ph1 = get(m,"Ph1"), ph2 = get(m,"Ph2"), ph3 = get(m,"Ph3"), ph4 = get(m,"Ph4");
    int busyNow = ph1 + ph2 + ph3 + ph4;

    // Finestra futura da simulare
    double end = now + horizon;
    List<Arrival> future = sliceArrivals(arr, now, end, currentIdx);
    int n = future.size();
    if (n == 0) {
      LAST_PRED_N = 0; LAST_PRED_REJ_RATE = 0.0;
      // Nulla da servire ⇒ non aumentiamo: basta garantire le repliche correnti
      return Math.max(POOL_MIN, busyNow);
    }

    ClassProb cp = readClassEntryProbs(m);

    // Prova target crescenti: da max(busyNow, POOL_MIN) fino a POOL_MAX + busyNow
    int lowerTot = Math.max(busyNow, POOL_MIN);
    int upperTot = Math.max(lowerTot, POOL_MAX); // TOT = busy + pool, con pool ≤ POOL_MAX

    for (int targetTot = lowerTot; targetTot <= upperTot; targetTot++) {
      // Snapshot iniziale
      SimSnap s = new SimSnap(now, poolNow, ph1, ph2, ph3, ph4);
      // Forza subito pool per ottenere il totale desiderato (cambio immediato)
      s.pool = Math.max(0, targetTot - s.busy());

      int rej = simulateWindowDeterministic_noSpin(s, future, targetTot, cp, rng);
      double rate = (double) rej / n;

      if (rate <= SLO) {
        LAST_PRED_N = n;
        LAST_PRED_REJ_RATE = rate;
        return targetTot;
      }
    }

    // Nessun totale rispetta SLO ⇒ usa il massimo provato (clamp esplicito)
    LAST_PRED_N = n;
    LAST_PRED_REJ_RATE = 1.0;
    return upperTot;
  }

  // Simula la finestra futura SENZA ritardi di scaling: totale costante
  static int simulateWindowDeterministic_noSpin(
      SimSnap s,
      List<Arrival> future,
      int targetTotReplicas,
      ClassProb cp,
      Random rng
  ){
    int rejections = 0;

    for (Arrival a : future) {
      // Avanza fino a a.t
      cheapAdvanceSnapshot(s, a.t - s.t, rng);

      // Totale costante: pool = targetTot - busy
      int needPool = Math.max(0, targetTotReplicas - s.busy());
      s.pool = Math.min(POOL_MAX, needPool);

      // Inject
      double[] p = (a.cls == 1) ? cp.c1 : (a.cls == 2) ? cp.c2 : cp.c3;
      if (s.pool <= 0) {
        rejections++;
      } else {
        s.pool--;
        int ph = pickPhaseWithProbs(p, rng);
        if      (ph == 1) s.ph1++;
        else if (ph == 2) s.ph2++;
        else if (ph == 3) s.ph3++;
        else              s.ph4++;
      }
      s.t = a.t;
    }
    return rejections;
  }

  // ----------------- Snapshot & utility per la simulazione -----------------

  static final class SimSnap {
    double t;
    int pool;
    int ph1, ph2, ph3, ph4;
    SimSnap(double t, int pool, int ph1, int ph2, int ph3, int ph4){
      this.t=t; this.pool=pool; this.ph1=ph1; this.ph2=ph2; this.ph3=ph3; this.ph4=ph4;
    }
    int busy(){ return ph1+ph2+ph3+ph4; }
  }

  static final class ClassProb {
    final double[] c1 = new double[5];
    final double[] c2 = new double[5];
    final double[] c3 = new double[5];
  }

  static ClassProb readClassEntryProbs(ModelOris2_fase4.GspnModel m){
    ClassProb cp = new ClassProb();
    double[] p1 = classEntryProb(m, 1);
    double[] p2 = classEntryProb(m, 2);
    double[] p3 = classEntryProb(m, 3);
    System.arraycopy(p1,0,cp.c1,0,5);
    System.arraycopy(p2,0,cp.c2,0,5);
    System.arraycopy(p3,0,cp.c3,0,5);
    return cp;
  }

  static int pickPhaseWithProbs(double[] probs, Random rng){
    double u = rng.nextDouble();
    double c1 = probs[1];
    double c2 = c1 + probs[2];
    double c3 = c2 + probs[3];
    if (u < c1) return 1;
    if (u < c2) return 2;
    if (u < c3) return 3;
    return 4;
  }

  static void cheapAdvanceSnapshot(SimSnap s, double dt, Random rng){
    if (dt <= 0) return;
    double tauMix = tauMixSnapshot(s);
    if (tauMix <= 0) { s.t += dt; return; }

    int busy = s.busy();
    if (busy <= 0) { s.t += dt; return; }

    double expected = (busy * dt) / tauMix;
    int completions = (int)Math.floor(expected);
    double frac = expected - completions;
    if (rng.nextDouble() < frac) completions++;

    if (completions > busy) completions = busy;
    if (completions <= 0) { s.t += dt; return; }

    int rem = completions;
    rem = takeFromPhaseSnap(() -> s.ph4, (v)-> s.ph4=v, rem);
    rem = takeFromPhaseSnap(() -> s.ph3, (v)-> s.ph3=v, rem);
    rem = takeFromPhaseSnap(() -> s.ph2, (v)-> s.ph2=v, rem);
    rem = takeFromPhaseSnap(() -> s.ph1, (v)-> s.ph1=v, rem);

    int done = completions - rem;
    if (done > 0) s.pool += done; // token tornano liberi
    s.t += dt;
  }

  interface IntGetter { int get(); }
  interface IntSetter { void set(int v); }
  static int takeFromPhaseSnap(IntGetter g, IntSetter s, int need){
    if (need <= 0) return 0;
    int have = g.get();
    int take = Math.min(have, need);
    if (take > 0) s.set(have - take);
    return need - take;
  }

  static double tauMixSnapshot(SimSnap s){
    double[] tau = phaseMeanTimesStatic();
    int b = s.busy();
    if (b <= 0) return 0.0;
    double w1 = s.ph1 / (double) b;
    double w2 = s.ph2 / (double) b;
    double w3 = s.ph3 / (double) b;
    double w4 = s.ph4 / (double) b;
    return w1*tau[1] + w2*tau[2] + w3*tau[3] + w4*tau[4];
  }

  static double[] phaseMeanTimesStatic() {
    int bph = (CACHED_BPH != null) ? CACHED_BPH : 10;
    double t1 = bph / 1.0, t2 = bph / 2.0, t3 = bph / 3.0, t4 = bph / 4.0;
    return new double[]{ 0.0, t1+t2+t3+t4, t2+t3+t4, t3+t4, t4 };
  }

  // ----------------- Avanzamento reale e integrazione idle -----------------

  static final class Delta {
    final double idle; // area (integrale) di idle su [t0,t1]
    final double dt;   // durata intervallo
    Delta(double idle, double dt){ this.idle = idle; this.dt = dt; }
  }

  static Delta advanceAndIntegrate(
      ModelOris2_fase4.GspnModel m, Random rng,
      double t0, double t1
  ){
    if (t1 <= t0) return new Delta(0.0, 0.0);
    double dt = t1 - t0;
    int poolStart = get(m,"Pool");
    cheapAdvance(m, dt, rng);
    int poolEnd = get(m,"Pool");
    double idleTrap = 0.5 * (poolStart + poolEnd) * dt; // trapezio
    return new Delta(idleTrap, dt);
  }

  // ----------------- Dinamica sul modello reale -----------------

  static void cheapAdvance(ModelOris2_fase4.GspnModel m, double dt, Random rng){
    if (dt <= 0) return;
    double tauMix = mixMeanService(m);
    if (tauMix <= 0) return;

    int busy = busyCount(m);
    if (busy <= 0) return;

    double expected = (busy * dt) / tauMix;
    int completions = (int)Math.floor(expected);
    double frac = expected - completions;
    if (rng.nextDouble() < frac) completions++;

    if (completions > busy) completions = busy;
    if (completions <= 0) return;

    int rem = completions;
    rem = takeFromPhase(m, "Ph4", rem);
    rem = takeFromPhase(m, "Ph3", rem);
    rem = takeFromPhase(m, "Ph2", rem);
    rem = takeFromPhase(m, "Ph1", rem);

    int done = completions - rem;
    if (done > 0) set(m, "Pool", get(m,"Pool") + done);
  }

  static int takeFromPhase(ModelOris2_fase4.GspnModel m, String ph, int need){
    if (need <= 0) return 0;
    int have = get(m, ph);
    int take = Math.min(have, need);
    if (take > 0) set(m, ph, have - take);
    return need - take;
  }

  // ----------------- Tempi medi / mix di servizio -----------------

  static double[] phaseMeanTimes(ModelOris2_fase4.GspnModel m) {
    int bph = get(m,"BphDiv");
    CACHED_BPH = bph; // aggiorna cache per lo snapshot
    double t1 = bph / 1.0, t2 = bph / 2.0, t3 = bph / 3.0, t4 = bph / 4.0;
    return new double[]{ 0.0, t1+t2+t3+t4, t2+t3+t4, t3+t4, t4 };
  }

  static double[] classEntryProb(ModelOris2_fase4.GspnModel m, int cls) {
    String p1="W11",p2="W12",p3="W13",p4="W14";
    if (cls==2){ p1="W21"; p2="W22"; p3="W23"; p4="W24"; }
    if (cls==3){ p1="W31"; p2="W32"; p3="W33"; p4="W34"; }
    int w1=get(m,p1), w2=get(m,p2), w3=get(m,p3), w4=get(m,p4);
    double sum = Math.max(1, w1+w2+w3+w4);
    return new double[]{0.0, w1/sum, w2/sum, w3/sum, w4/sum};
  }

  static double classMeanService(ModelOris2_fase4.GspnModel m, int cls) {
    double[] tau = phaseMeanTimes(m);
    double[] p   = classEntryProb(m, cls);
    return p[1]*tau[1] + p[2]*tau[2] + p[3]*tau[3] + p[4]*tau[4];
  }

  static double mixMeanService(ModelOris2_fase4.GspnModel m) {
    // Se i Rate sono a 0 (disabilitati), usa un mix di default solo per calcolare tauMix
    int r1 = get(m,"Rate1"), r2 = get(m,"Rate2"), r3 = get(m,"Rate3");
    if (r1+r2+r3 == 0){ r1 = 5; r2 = 3; r3 = 2; }
    double tau1 = classMeanService(m,1);
    double tau2 = classMeanService(m,2);
    double tau3 = classMeanService(m,3);
    double sum = r1 + r2 + r3;
    return (r1*tau1 + r2*tau2 + r3*tau3) / Math.max(1.0, sum);
  }

  // ----------------- Probability switch & inject -----------------

  static boolean injectArrival(ModelOris2_fase4.GspnModel m, int cls, Random rng){
    int pool = get(m,"Pool");
    if (pool <= 0) return false; // rejection
    int ph = pickPhase(m, cls, rng);
    String dest = (ph==1)?"Ph1":(ph==2)?"Ph2":(ph==3)?"Ph3":"Ph4";
    set(m,"Pool", pool-1);
    set(m, dest, get(m,dest)+1);
    return true;
  }

  static int pickPhase(ModelOris2_fase4.GspnModel m, int cls, Random rng){
    String p1="W11",p2="W12",p3="W13",p4="W14";
    if (cls==2){ p1="W21"; p2="W22"; p3="W23"; p4="W24"; }
    if (cls==3){ p1="W31"; p2="W32"; p3="W33"; p4="W34"; }
    int w1=get(m,p1), w2=get(m,p2), w3=get(m,p3), w4=get(m,p4);
    int sum = Math.max(1, w1+w2+w3+w4);
    double u = rng.nextDouble();
    double c1 = w1/(double)sum, c2 = c1 + w2/(double)sum, c3 = c2 + w3/(double)sum;
    if (u < c1) return 1;
    if (u < c2) return 2;
    if (u < c3) return 3;
    return 4;
  }

  // ----------------- I/O & utilità -----------------

  static List<Arrival> readArrivals(String file) throws Exception {
    List<Arrival> res = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line = br.readLine(); // header
      while ((line = br.readLine()) != null) {
        String[] s = line.trim().split(",");
        if (s.length < 2) continue;
        double t = Double.parseDouble(s[0]);
        int cls = Integer.parseInt(s[1]);
        res.add(new Arrival(t, cls));
      }
    }
    res.sort(Comparator.comparingDouble(a -> a.t));
    return res;
  }

  static List<Arrival> sliceArrivals(List<Arrival> arr, double from, double to, int startIdx){
    List<Arrival> out = new ArrayList<>();
    for (int i = Math.max(0, startIdx); i < arr.size(); i++){
      Arrival a = arr.get(i);
      if (a.t < from) continue;
      if (a.t >= to) break;
      out.add(a);
    }
    return out;
  }

  static void writeIntervalIfClosed(PrintWriter csvInt,
                                    double tStart, double tEnd,
                                    int poolNow, int targetTot, double effChangeTime,
                                    int arrivalsInInterval, int rejectsInInterval, double idleIntegralInterval,
                                    double predRejAtTarget, int predN) {
    if (tEnd <= tStart + 1e-12) return;
    double dt = tEnd - tStart;
    double idleMeanInt = idleIntegralInterval / dt;
    double rejRateInt  = (arrivalsInInterval > 0) ? (double) rejectsInInterval / arrivalsInInterval : 0.0;

    csvInt.printf(Locale.US,
        "%.3f,%.3f,%d,%d,%.3f,%d,%d,%.6f,%.3f,%.6f,%d%n",
        tStart, tEnd, poolNow, targetTot, effChangeTime,
        arrivalsInInterval, rejectsInInterval, rejRateInt, idleMeanInt,
        predRejAtTarget, predN);
  }

  static void disableAutomaticArrivals(ModelOris2_fase4.GspnModel m){
    set(m,"Rate1",0); set(m,"Rate2",0); set(m,"Rate3",0);
    set(m,"RateDiv",1);
    CACHED_BPH = get(m,"BphDiv"); // cache per lo snapshot
  }

  static double roundUpToGrid(double t, double step){
    double k = Math.ceil(t / step);
    return k * step;
  }

  static int busyCount(ModelOris2_fase4.GspnModel m){
    return get(m,"Ph1")+get(m,"Ph2")+get(m,"Ph3")+get(m,"Ph4");
  }

  static int get(ModelOris2_fase4.GspnModel m, String place){
    return m.marking.getTokens(m.net.getPlace(place));
  }
  static void set(ModelOris2_fase4.GspnModel m, String place, int v){
    m.marking.setTokens(m.net.getPlace(place), v);
  }
}
