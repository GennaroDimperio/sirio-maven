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
 *  1) legge la time series dal file arrivals.csv
 *  2) disattiva gli arrivi automatici del modello GSPN 
 *  3) ogni CONTROL_PERIOD_SEC:
 *      - stima i rate sugli ultimi ESTIMATE_WINDOW_SEC
 *      - sceglie il totale minimo di repliche (pool+busy) per i prossimi PREDICTION_HORIZON_SEC
 *        tale che la rejection prevista ≤ TARGET_REJECTION
 *      - imposta subito i token in Pool per raggiungere quel totale
 *  4) tra un evento e l’altro fa avanzare il modello con la “gara di esponenziali”
 *     e integra l’idle (area sotto Pool)
 *
 * Output:
 *  - stdout con metriche globali
 *  - CSV: timeseries_sli.csv (riassunto) e timeseries_intervals.csv (per intervalli)
 */
public class TimeseriesSimulator {

  // ===== Parametri controller e stima =====
  static final double ESTIMATE_WINDOW_SEC    = 20.0;  // ampiezza finestra stima rate
  static final double CONTROL_PERIOD_SEC     = 10.0;  // periodo del controllore
  static final double PREDICTION_HORIZON_SEC = 10.0;  // orizzonte predizione
  static final double TARGET_REJECTION       = 0.01;  // SLO: max rejection rate

  // ===== Limiti risorse =====
  static final int MIN_POOL = 1;
  static final int MAX_POOL = 24;

  // ===== Logging =====
  static final int PROGRESS_EVERY = 50;

  // ===== Diagnostica predizione =====
  static double lastPredictedRejectionRate = Double.NaN;
  static int    lastPredictedSampleCount   = 0;

  // ===== Strutture dati =====
  static final class ArrivalEvent {
    final double timeSec;
    final int    classId;
    ArrivalEvent(double t, int c){ this.timeSec = t; this.classId = c; }
  }

  // Cache di BphDiv per lo snapshot
  static Integer cachedBphDiv = null;

  public static void main(String[] args) throws Exception {
    final String arrivalsPath = (args != null && args.length > 0) ? args[0] : "arrivals.csv";

    ModelOris2_fase4.GspnModel model = ModelOris2_fase4.build();
    disableModelAutomaticArrivals(model);

    List<ArrivalEvent> arrivals = loadArrivalsCsv(arrivalsPath);
    System.out.println("[info] arrivals file: " + arrivalsPath + "  |  letti " + arrivals.size() + " arrivi");
    if (arrivals.isEmpty()) return;

    SlidingRateEstimator estimator = new SlidingRateEstimator(ESTIMATE_WINDOW_SEC, CONTROL_PERIOD_SEC);
    Random rng = new Random(777);

    // Metriche globali
    double simTimeAccum = 0.0;
    double idleAreaAccum = 0.0;
    int totalRejections = 0;

    // Metriche per intervallo
    double intervalStart = arrivals.get(0).timeSec;
    int arrivalsInInterval = 0;
    int rejectsInInterval  = 0;
    double idleAreaInterval = 0.0;
    int    lastChosenTotal   = -1;
    double lastEffectiveChangeTime = -1.0;

    try (PrintWriter csvIntervals = new PrintWriter(new FileWriter("timeseries_intervals.csv"));
         PrintWriter csvSummary   = new PrintWriter(new FileWriter("timeseries_sli.csv"))) {

      csvIntervals.println("t_start,t_end,pool_now,target_tot,eff_change_time,arrivals,rejections,rejection_rate,idle_mean_interval,pred_rej_at_target,pred_n");
      csvSummary.println("total_time_s,rejections,rejection_rate,idle_mean");

      double simClock = arrivals.get(0).timeSec;
      double nextControl = ceilToGrid(simClock, CONTROL_PERIOD_SEC);

      for (int i = 0; i < arrivals.size(); i++) {
        ArrivalEvent ev = arrivals.get(i);

        // Il controller può scattare più volte prima del prossimo arrivo
        while (nextControl <= ev.timeSec) {
          // Avanza dinamica fino a nextControl e integra idle
          StepDelta d = advanceModelAndIntegrateIdle(model, rng, simClock, nextControl);
          idleAreaAccum    += d.idleArea;
          idleAreaInterval += d.idleArea;
          simTimeAccum     += d.dt;
          simClock          = nextControl;

          SlidingRateEstimator.Rates rates = estimator.estimateRatesAt(nextControl);

          // Calcolo il totale minimo di repliche che rispetta lo SLO nel prossimo orizzonte
          int chosenTotalReplicas = chooseMinReplicasMeetingSLO(
              model, nextControl, PREDICTION_HORIZON_SEC, arrivals, i, rng
          );

          int busyNow  = countBusy(model);
          int needPool = Math.max(0, chosenTotalReplicas - busyNow);
          setTokens(model, "Pool", Math.max(MIN_POOL, Math.min(MAX_POOL, needPool)));

          lastChosenTotal         = chosenTotalReplicas;
          lastEffectiveChangeTime = nextControl;

          int poolNow = getTokens(model,"Pool");
          writeIntervalRowIfClosed(csvIntervals, intervalStart, nextControl,
              poolNow, lastChosenTotal, lastEffectiveChangeTime,
              arrivalsInInterval, rejectsInInterval, idleAreaInterval,
              lastPredictedRejectionRate, lastPredictedSampleCount);

          intervalStart        = nextControl;
          arrivalsInInterval   = 0;
          rejectsInInterval    = 0;
          idleAreaInterval     = 0.0;

          nextControl += CONTROL_PERIOD_SEC;
        }

        // Registra arrivo per la stima (finestra mobile)
        estimator.add(ev.timeSec, ev.classId);

        // Avanza fino all'arrivo e integra l'idle
        StepDelta d = advanceModelAndIntegrateIdle(model, rng, simClock, ev.timeSec);
        idleAreaAccum    += d.idleArea;
        idleAreaInterval += d.idleArea;
        simTimeAccum     += d.dt;
        simClock          = ev.timeSec;

        // Inject dell'arrivo: instrada verso Ph1..Ph4 secondo i pesi Wxy
        boolean accepted = injectArrival(model, ev.classId, rng);
        if (!accepted) { totalRejections++; rejectsInInterval++; }
        arrivalsInInterval++;

        if ((i+1) % PROGRESS_EVERY == 0) {
          System.out.printf(Locale.US,
              "[progress] %d/%d  t=%.3f  rej=%d  Pool=%d  busy=%d%n",
              (i+1), arrivals.size(), ev.timeSec, totalRejections, getTokens(model,"Pool"), countBusy(model));
        }
      }

      // Chiusura ultimo intervallo a fine serie
      double lastT = arrivals.get(arrivals.size()-1).timeSec;
      int poolNowEnd = getTokens(model, "Pool");
      writeIntervalRowIfClosed(csvIntervals, intervalStart, lastT,
          poolNowEnd, lastChosenTotal, lastEffectiveChangeTime,
          arrivalsInInterval, rejectsInInterval, idleAreaInterval,
          lastPredictedRejectionRate, lastPredictedSampleCount);

      // Metriche complessive
      double idleMean = (simTimeAccum > 0) ? idleAreaAccum / simTimeAccum : 0.0;
      double rejRate  = !arrivals.isEmpty() ? (double) totalRejections / arrivals.size() : 0.0;

      System.out.println();
      System.out.println("== RISULTATI TIMESERIES ==");
      System.out.println("Tempo totale simulato: " + String.format(Locale.US,"%.3f", simTimeAccum) + " s");
      System.out.println("Rejection totali:      " + totalRejections);
      System.out.println("Rejection rate:        " + String.format(Locale.US,"%.6f", rejRate));
      System.out.println("Idle medio (Pool):     " + String.format(Locale.US,"%.3f", idleMean));

      csvSummary.printf(Locale.US, "%.3f,%d,%.6f,%.3f%n", simTimeAccum, totalRejections, rejRate, idleMean);
      System.out.println("CSV scritto: timeseries_sli.csv");
      System.out.println("CSV per intervalli: timeseries_intervals.csv");
    }
  }

  // ----------------- Controller -----------------

  /**
   * Sceglie il minimo totale di repliche (pool+busy) tale che,
   * simulando i prossimi 'horizon' secondi con totale costante,
   * la rejection prevista ≤ TARGET_REJECTION.
   */
  static int chooseMinReplicasMeetingSLO(
      ModelOris2_fase4.GspnModel model,
      double now,
      double horizon,
      List<ArrivalEvent> allArrivals,
      int currentIdx,
      Random rng
  ){
    // Stato attuale
    int poolNow = getTokens(model,"Pool");
    int ph1 = getTokens(model,"Ph1"), ph2 = getTokens(model,"Ph2"), ph3 = getTokens(model,"Ph3"), ph4 = getTokens(model,"Ph4");
    int busyNow = ph1 + ph2 + ph3 + ph4;

    // Finestra futura da simulare
    double end = now + horizon;
    List<ArrivalEvent> future = sliceArrivalsInWindow(allArrivals, now, end, currentIdx);
    int n = future.size();
    if (n == 0) {
      lastPredictedSampleCount   = 0;
      lastPredictedRejectionRate = 0.0;
      return Math.max(MIN_POOL, busyNow);
    }

    ClassProb entryProbs = readClassPhaseEntryProbs(model);

    // Prova target crescenti: da max(busyNow, MIN_POOL) a busyNow+MAX_POOL
    int lowerTot = Math.max(busyNow, MIN_POOL);
    int upperTot = Math.max(lowerTot, busyNow + MAX_POOL);

    for (int targetTot = lowerTot; targetTot <= upperTot; targetTot++) {

      SimSnapshot s = new SimSnapshot(now, poolNow, ph1, ph2, ph3, ph4);
      s.pool = Math.max(0, targetTot - s.busy());

      int rej = simulateHorizonNoSpin(s, future, targetTot, entryProbs, rng);
      double rate = (double) rej / n;

      if (rate <= TARGET_REJECTION) {
        lastPredictedSampleCount   = n;
        lastPredictedRejectionRate = rate;
        return targetTot;
      }
    }

    // Nessun totale rispetta lo SLO quindi uso il massimo provato
    lastPredictedSampleCount   = n;
    lastPredictedRejectionRate = 1.0;
    return upperTot;
  }

  static int simulateHorizonNoSpin(
      SimSnapshot s,
      List<ArrivalEvent> future,
      int targetTotReplicas,
      ClassProb entryProbs,
      Random rng
  ){
    int rejections = 0;

    for (ArrivalEvent a : future) {
      // Avanza fino a a.timeSec
      advanceSnapshotByCompetingExponentials(s, a.timeSec - s.time, rng);

      // Totale costante: pool = targetTot - busy
      int needPool = Math.max(0, targetTotReplicas - s.busy());
      s.pool = Math.min(MAX_POOL, needPool);

      // Inject
      double[] p = (a.classId == 1) ? entryProbs.c1 : (a.classId == 2) ? entryProbs.c2 : entryProbs.c3;
      if (s.pool <= 0) {
        rejections++;
      } else {
        s.pool--;
        int ph = pickPhaseFromProbs(p, rng);
        if      (ph == 1) s.ph1++;
        else if (ph == 2) s.ph2++;
        else if (ph == 3) s.ph3++;
        else              s.ph4++;
      }
      s.time = a.timeSec;
    }
    return rejections;
  }

  // ----------------- Snapshot & utility per la simulazione -----------------

  static final class SimSnapshot {
    double time;
    int pool;
    int ph1, ph2, ph3, ph4;
    SimSnapshot(double t, int pool, int ph1, int ph2, int ph3, int ph4){
      this.time=t; this.pool=pool; this.ph1=ph1; this.ph2=ph2; this.ph3=ph3; this.ph4=ph4;
    }
    int busy(){ return ph1+ph2+ph3+ph4; }
  }

  static final class ClassProb {
    final double[] c1 = new double[5];
    final double[] c2 = new double[5];
    final double[] c3 = new double[5];
  }

  static ClassProb readClassPhaseEntryProbs(ModelOris2_fase4.GspnModel model){
    ClassProb cp = new ClassProb();
    double[] p1 = classPhaseEntryProbs(model, 1);
    double[] p2 = classPhaseEntryProbs(model, 2);
    double[] p3 = classPhaseEntryProbs(model, 3);
    System.arraycopy(p1,0,cp.c1,0,5);
    System.arraycopy(p2,0,cp.c2,0,5);
    System.arraycopy(p3,0,cp.c3,0,5);
    return cp;
  }

  static int pickPhaseFromProbs(double[] probs, Random rng){
    double u = rng.nextDouble();
    double c1 = probs[1];
    double c2 = c1 + probs[2];
    double c3 = c2 + probs[3];
    if (u < c1) return 1;
    if (u < c2) return 2;
    if (u < c3) return 3;
    return 4;
  }

  static void advanceSnapshotByCompetingExponentials(SimSnapshot s, double dt, Random rng){
    if (dt <= 0) return;
    double t = 0.0;
    final int bph = Math.max(1, (cachedBphDiv != null ? cachedBphDiv : 10));

    while (true) {
      double r1 = (s.ph1 > 0) ? (1.0 * s.ph1) / bph : 0.0;
      double r2 = (s.ph2 > 0) ? (2.0 * s.ph2) / bph : 0.0;
      double r3 = (s.ph3 > 0) ? (3.0 * s.ph3) / bph : 0.0;
      double r4 = (s.ph4 > 0) ? (4.0 * s.ph4) / bph : 0.0;
      double R = r1 + r2 + r3 + r4;
      if (R <= 0.0) { s.time += (dt - t); return; }

      double tau = sampleExp(R, rng);
      if (t + tau >= dt) { s.time += (dt - t); return; }

      t += tau;
      int ev = sampleIndexByWeights(new double[]{r1, r2, r3, r4}, rng);
      if (ev == 0 && s.ph1 > 0) { s.ph1--; s.ph2++; }
      else if (ev == 1 && s.ph2 > 0) { s.ph2--; s.ph3++; }
      else if (ev == 2 && s.ph3 > 0) { s.ph3--; s.ph4++; }
      else if (ev == 3 && s.ph4 > 0) { s.ph4--; s.pool++; }
    }
  }

  static double tauMixSnapshot(SimSnapshot s){
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
    int bph = (cachedBphDiv != null) ? cachedBphDiv : 10;
    double t1 = bph / 1.0, t2 = bph / 2.0, t3 = bph / 3.0, t4 = bph / 4.0;
    return new double[]{ 0.0, t1+t2+t3+t4, t2+t3+t4, t3+t4, t4 };
  }

  // ----------------- Avanzamento reale e integrazione idle -----------------

  static final class StepDelta {
    final double idleArea; // integrale di Pool su [t0,t1]
    final double dt;
    StepDelta(double idleArea, double dt){ this.idleArea = idleArea; this.dt = dt; }
  }

  static StepDelta advanceModelAndIntegrateIdle(
      ModelOris2_fase4.GspnModel model, Random rng,
      double t0, double t1
  ){
    if (t1 <= t0) return new StepDelta(0.0, 0.0);

    double t = t0;
    double idleArea = 0.0;
    int pool = getTokens(model,"Pool");
    final int bph = Math.max(1, getTokens(model,"BphDiv"));

    while (true) {
      // rate t1..t4 in base ai token presenti
      int ph1 = getTokens(model,"Ph1"), ph2 = getTokens(model,"Ph2"), ph3 = getTokens(model,"Ph3"), ph4 = getTokens(model,"Ph4");
      double r1 = (ph1 > 0) ? (1.0 * ph1) / bph : 0.0;
      double r2 = (ph2 > 0) ? (2.0 * ph2) / bph : 0.0;
      double r3 = (ph3 > 0) ? (3.0 * ph3) / bph : 0.0;
      double r4 = (ph4 > 0) ? (4.0 * ph4) / bph : 0.0;
      double R = r1 + r2 + r3 + r4;

      if (R <= 0.0) {
        idleArea += pool * (t1 - t);
        break;
      }

      double tau = sampleExp(R, rng);
      if (t + tau >= t1) {
        idleArea += pool * (t1 - t);
        break;
      }

      // integra idle fino al prossimo completamento
      idleArea += pool * tau;
      t += tau;

      // determina quale transizione "scatta"
      int ev = sampleIndexByWeights(new double[]{r1, r2, r3, r4}, rng);
      if (ev == 0 && ph1 > 0) { setTokens(model,"Ph1", ph1 - 1); setTokens(model,"Ph2", ph2 + 1); }
      else if (ev == 1 && ph2 > 0) { setTokens(model,"Ph2", ph2 - 1); setTokens(model,"Ph3", ph3 + 1); }
      else if (ev == 2 && ph3 > 0) { setTokens(model,"Ph3", ph3 - 1); setTokens(model,"Ph4", ph4 + 1); }
      else if (ev == 3 && ph4 > 0) { setTokens(model,"Ph4", ph4 - 1); setTokens(model,"Pool", pool + 1); pool = pool + 1; }
      // NB: aggiorno 'pool' locale solo quando Ph4->Pool
    }

    return new StepDelta(idleArea, t1 - t0);
  }

  // ----------------- Dinamica sul modello reale -----------------

  static void advanceModelByCompetingExponentials(ModelOris2_fase4.GspnModel model, double dt, Random rng){
    if (dt <= 0) return;

    double t = 0.0;
    final int bph = Math.max(1, getTokens(model,"BphDiv"));

    while (true) {
      int ph1 = getTokens(model,"Ph1"), ph2 = getTokens(model,"Ph2"), ph3 = getTokens(model,"Ph3"), ph4 = getTokens(model,"Ph4");
      double r1 = (ph1 > 0) ? (1.0 * ph1) / bph : 0.0;
      double r2 = (ph2 > 0) ? (2.0 * ph2) / bph : 0.0;
      double r3 = (ph3 > 0) ? (3.0 * ph3) / bph : 0.0;
      double r4 = (ph4 > 0) ? (4.0 * ph4) / bph : 0.0;
      double R = r1 + r2 + r3 + r4;

      if (R <= 0.0) return;

      double tau = sampleExp(R, rng);
      if (t + tau >= dt) return;

      t += tau;
      int ev = sampleIndexByWeights(new double[]{r1, r2, r3, r4}, rng);
      if (ev == 0 && ph1 > 0) { setTokens(model,"Ph1", ph1 - 1); setTokens(model,"Ph2", ph2 + 1); }
      else if (ev == 1 && ph2 > 0) { setTokens(model,"Ph2", ph2 - 1); setTokens(model,"Ph3", ph3 + 1); }
      else if (ev == 2 && ph3 > 0) { setTokens(model,"Ph3", ph3 - 1); setTokens(model,"Ph4", ph4 + 1); }
      else if (ev == 3 && ph4 > 0) { setTokens(model,"Ph4", ph4 - 1); setTokens(model,"Pool", getTokens(model,"Pool") + 1); }
    }
  }

  // ----------------- Tempi medi e mix di servizio -----------------

  static double[] phaseMeanTimes(ModelOris2_fase4.GspnModel model) {
    int bph = getTokens(model,"BphDiv");
    cachedBphDiv = bph; // aggiorna cache per lo snapshot
    double t1 = bph / 1.0, t2 = bph / 2.0, t3 = bph / 3.0, t4 = bph / 4.0;
    return new double[]{ 0.0, t1+t2+t3+t4, t2+t3+t4, t3+t4, t4 };
  }

  static double[] classPhaseEntryProbs(ModelOris2_fase4.GspnModel model, int cls) {
    String p1="W11",p2="W12",p3="W13",p4="W14";
    if (cls==2){ p1="W21"; p2="W22"; p3="W23"; p4="W24"; }
    if (cls==3){ p1="W31"; p2="W32"; p3="W33"; p4="W34"; }
    int w1=getTokens(model,p1), w2=getTokens(model,p2), w3=getTokens(model,p3), w4=getTokens(model,p4);
    double sum = Math.max(1, w1+w2+w3+w4);
    return new double[]{0.0, w1/sum, w2/sum, w3/sum, w4/sum};
  }

  static double classMeanService(ModelOris2_fase4.GspnModel model, int cls) {
    double[] tau = phaseMeanTimes(model);
    double[] p   = classPhaseEntryProbs(model, cls);
    return p[1]*tau[1] + p[2]*tau[2] + p[3]*tau[3] + p[4]*tau[4];
  }

  static double mixMeanService(ModelOris2_fase4.GspnModel model) {
    // Se i Rate sono a 0 (disabilitati), usa un mix di default solo per calcolare tauMix
    int r1 = getTokens(model,"Rate1"), r2 = getTokens(model,"Rate2"), r3 = getTokens(model,"Rate3");
    if (r1+r2+r3 == 0){ r1 = 5; r2 = 3; r3 = 2; }
    double tau1 = classMeanService(model,1);
    double tau2 = classMeanService(model,2);
    double tau3 = classMeanService(model,3);
    double sum = r1 + r2 + r3;
    return (r1*tau1 + r2*tau2 + r3*tau3) / Math.max(1.0, sum);
  }

  // ----------------- Probability switch e inject -----------------

  static boolean injectArrival(ModelOris2_fase4.GspnModel model, int cls, Random rng){
    int pool = getTokens(model,"Pool");
    if (pool <= 0) return false; // rejection
    int ph = pickEntryPhase(model, cls, rng);
    String dest = (ph==1)?"Ph1":(ph==2)?"Ph2":(ph==3)?"Ph3":"Ph4";
    setTokens(model,"Pool", pool-1);
    setTokens(model, dest, getTokens(model,dest)+1);
    return true;
  }

  static int pickEntryPhase(ModelOris2_fase4.GspnModel model, int cls, Random rng){
    String p1="W11",p2="W12",p3="W13",p4="W14";
    if (cls==2){ p1="W21"; p2="W22"; p3="W23"; p4="W24"; }
    if (cls==3){ p1="W31"; p2="W32"; p3="W33"; p4="W34"; }
    int w1=getTokens(model,p1), w2=getTokens(model,p2), w3=getTokens(model,p3), w4=getTokens(model,p4);
    int sum = Math.max(1, w1+w2+w3+w4);
    double u = rng.nextDouble();
    double c1 = w1/(double)sum, c2 = c1 + w2/(double)sum, c3 = c2 + w3/(double)sum;
    if (u < c1) return 1;
    if (u < c2) return 2;
    if (u < c3) return 3;
    return 4;
  }

  // ----------------- I/O e utilità -----------------

  static List<ArrivalEvent> loadArrivalsCsv(String file) throws Exception {
    List<ArrivalEvent> out = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line = br.readLine(); // header
      while ((line = br.readLine()) != null) {
        String[] s = line.trim().split(",");
        if (s.length < 2) continue;
        double t = Double.parseDouble(s[0]);
        int cls = Integer.parseInt(s[1]);
        out.add(new ArrivalEvent(t, cls));
      }
    }
    out.sort(Comparator.comparingDouble(a -> a.timeSec));
    return out;
  }

  static List<ArrivalEvent> sliceArrivalsInWindow(List<ArrivalEvent> arr, double from, double to, int startIdx){
    List<ArrivalEvent> out = new ArrayList<>();
    for (int i = Math.max(0, startIdx); i < arr.size(); i++){
      ArrivalEvent a = arr.get(i);
      if (a.timeSec < from) continue;
      if (a.timeSec >= to) break;
      out.add(a);
    }
    return out;
  }

  static void writeIntervalRowIfClosed(PrintWriter csvInt,
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

  static void disableModelAutomaticArrivals(ModelOris2_fase4.GspnModel model){
    setTokens(model,"Rate1",0); setTokens(model,"Rate2",0); setTokens(model,"Rate3",0);
    setTokens(model,"RateDiv",1);
    cachedBphDiv = getTokens(model,"BphDiv"); // cache per lo snapshot
  }

  static double ceilToGrid(double t, double step){
    double k = Math.ceil(t / step);
    return k * step;
  }

  static int countBusy(ModelOris2_fase4.GspnModel model){
    return getTokens(model,"Ph1")+getTokens(model,"Ph2")+getTokens(model,"Ph3")+getTokens(model,"Ph4");
  }

  static int getTokens(ModelOris2_fase4.GspnModel model, String place){
    return model.marking.getTokens(model.net.getPlace(place));
  }
  static void setTokens(ModelOris2_fase4.GspnModel model, String place, int v){
    model.marking.setTokens(model.net.getPlace(place), v);
  }

  static double sampleExp(double rate, Random rng){
    double u = Math.max(1e-12, 1.0 - rng.nextDouble());
    return -Math.log(u) / rate;
  }

  static int sampleIndexByWeights(double[] w, Random rng){
    double sum = 0.0; for (double v : w) sum += v;
    if (sum <= 0.0) return 0;
    double u = rng.nextDouble() * sum, acc = 0.0;
    for (int i=0;i<w.length;i++){ acc += w[i]; if (u <= acc) return i; }
    return w.length - 1;
  }
}
