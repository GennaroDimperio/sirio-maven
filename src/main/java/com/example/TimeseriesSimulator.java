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
import java.util.Scanner;

/**
 * TimeseriesSimulator
 *
 * Cosa fa:
 *  1) Legge gli arrivi (t, classe) da arrivals.csv
 *  2) Disattiva gli arrivi automatici nel modello GSPN
 *  3) Ogni periodo di controllo decide il totale di repliche (busy+pool):
 *     - Mode DEFAULT: previsione con orizzonte default
 *     - Mode CUSTOM:  previsione con orizzonte/periodo scelti a runtime
 *     - Mode NO_FUTURE: nessuna previsione
 *  4) Tra eventi: fa avanzare il modello con gara di esponenziali e integra l'idle
 *
 * Output CSV:
 *  - timeseries_sli_<mode>.csv        (riassunto)
 *  - timeseries_intervals_<mode>.csv  (intervalli)
 *  - timeseries_debug.csv             (primi 20 eventi, "time|event")
 */
public class TimeseriesSimulator {

  // Parametri principali
  static final double WINDOW_SEC  = 20.0;  // finestra per stima rate
  static final double CONTROL_SEC = 10.0;  // periodo del controller
  static final double HORIZON_SEC = 10.0;  // orizzonte di previsione
  static final double SLO_REJECT  = 0.01;  // soglia max rejection

  enum Mode { DEFAULT, CUSTOM, NO_FUTURE }

  // Modalità correnti
  static Mode currentMode = Mode.DEFAULT;
  static double horizonSec = HORIZON_SEC;
  static double controlSec = CONTROL_SEC;

  // Limiti risorse
  static final int POOL_MIN = 1;
  static final int POOL_MAX = 24;

  // Logging
  static final int LOG_EVERY = 50;
  static final int DEBUG_MAX = 20;
  static int debugLog = 0;

  // Diagnostica predizione (per CSV intervalli)
  static double lastPredReject = Double.NaN;
  static int    lastPredN      = 0;

  // Cache divisore per rate fasi (BphDiv)
  static Integer bphDivCached = null;

  // Arrivi
  static final class Arrival {
    final double time;
    final int cls;
    Arrival(double t, int c){ this.time = t; this.cls = c; }
  }

  // Tag per i nomi file in base alla modalità
  static String modeTag() {
    return switch (currentMode) {
      case DEFAULT   -> "default";
      case CUSTOM    -> String.format(Locale.US, "custom_h%.0f_p%.0f", horizonSec, controlSec);
      case NO_FUTURE -> "nofuture";
    };
  }

  public static void main(String[] args) throws Exception {
    final String arrivalsPath = (args != null && args.length > 0) ? args[0] : "arrivals.csv";

Scanner sc = new Scanner(System.in);
boolean sceltaValida = false;

    while (!sceltaValida) {
      System.out.println("Scegliere modalità simulazione:");
      System.out.println("1 = previsione default");
      System.out.println("2 = previsione con parametri custom");
      System.out.println("3 = senza previsione (solo stato corrente)");
      System.out.print("Inserire scelta: ");

      if (!sc.hasNextInt()) {
        System.out.println("[errore] Inserisci un numero intero (1, 2 o 3).");
        sc.next(); 
        continue;
      }

      int choice = sc.nextInt();
      if (choice == 1) {
        currentMode = Mode.DEFAULT;
        horizonSec  = HORIZON_SEC;
        controlSec  = CONTROL_SEC;
        sceltaValida = true;

      } else if (choice == 2) {
        currentMode = Mode.CUSTOM;
        System.out.print("Inserire orizzonte di previsione (sec): ");
        horizonSec = sc.nextDouble();
        System.out.print("Inserire periodo di controllo (sec): ");
        controlSec = sc.nextDouble();

        if (horizonSec > 0 && controlSec > 0) {
          System.out.printf(Locale.US,
              "[mode] CUSTOM | horizon=%.3f s | control=%.3f s%n",
              horizonSec, controlSec);
          sceltaValida = true;
        } else {
          System.out.println("[errore] Orizzonte e periodo devono essere > 0.");
        }

      } else if (choice == 3) {
        currentMode = Mode.NO_FUTURE; // nessuna previsione
        sceltaValida = true;

      } else {
        System.out.println("[errore] Scelta non valida, inserire 1, 2 o 3.");
      }
    }

    sc.close();


    // Modello 
    ModelOris2_fase4.GspnModel model = ModelOris2_fase4.build();
    disableAutomaticArrivals(model);

    // Arrivi 
    List<Arrival> arrivals = readArrivalsCsv(arrivalsPath);
    System.out.println("[info] file arrivi: " + arrivalsPath + " | letti " + arrivals.size() + " arrivi");
    if (arrivals.isEmpty()) return;

    Random rng = new Random(777);

    // Metriche globali
    double simTime = 0.0;
    double idleSum = 0.0;
    int totalRejects = 0;

    // Metriche intervallo
    double intervalStart = arrivals.get(0).time;
    int    intervalArr   = 0;
    int    intervalRej   = 0;
    double intervalIdle  = 0.0;
    int    lastTargetTot = -1;
    double lastApplyTime = -1.0;

    // CSV file
    String tag = modeTag();
    String intervalsFile = String.format("timeseries_intervals_%s.csv", tag);
    String summaryFile   = String.format("timeseries_sli_%s.csv", tag);

    try (PrintWriter csvIntervals = new PrintWriter(new FileWriter(intervalsFile));
         PrintWriter csvSummary   = new PrintWriter(new FileWriter(summaryFile));
         PrintWriter csvDebug     = new PrintWriter(new FileWriter("timeseries_debug.csv"))) {

      csvIntervals.println("t_start,t_end,pool_now,target_tot,eff_change_time,arrivals,rejections,rejection_rate,idle_mean_interval,pred_rej_at_target,pred_n");
      csvSummary.println("total_time_s,rejections,rejection_rate,idle_mean");
      csvDebug.println("time|event"); // header debug umano

      double clock = arrivals.get(0).time;
      double nextControl = ceilToStep(clock, controlSec);

      for (int i = 0; i < arrivals.size(); i++) {
        Arrival ev = arrivals.get(i);

        // Controller può “scattare” più volte prima del prossimo arrivo
        while (nextControl <= ev.time) {
          Step s = advanceModelAndIntegrateIdle(model, rng, clock, nextControl, csvDebug, debugLog < DEBUG_MAX);
          idleSum      += s.idleArea;
          intervalIdle += s.idleArea;
          simTime      += s.dt;
          clock         = nextControl;

          // Decido il totale target in base alla modalità
          int targetTotal;
          switch (currentMode) {
            case DEFAULT -> targetTotal = chooseMinReplicas(model, nextControl, HORIZON_SEC, arrivals, i, rng);
            case CUSTOM  -> targetTotal = chooseMinReplicas(model, nextControl, horizonSec, arrivals, i, rng);
            case NO_FUTURE -> {
              int busyNow = countBusy(model);
              int poolNow = getTokens(model, "Pool");
              targetTotal = Math.max(POOL_MIN, busyNow + poolNow);
              lastPredReject = Double.NaN;
              lastPredN = 0;
            }
            default -> targetTotal = countBusy(model);
          }

          // Applico: Pool = clamp(targetTotal - busy, [POOL_MIN, POOL_MAX])
          int busyNow  = countBusy(model);
          int needPool = Math.max(0, targetTotal - busyNow);
          needPool = Math.max(POOL_MIN, Math.min(POOL_MAX, needPool));
          setTokens(model, "Pool", needPool);

          lastTargetTot = targetTotal;
          lastApplyTime = nextControl;

          int poolNow = getTokens(model, "Pool");
          writeIntervalRow(csvIntervals, intervalStart, nextControl,
              poolNow, lastTargetTot, lastApplyTime,
              intervalArr, intervalRej, intervalIdle,
              lastPredReject, lastPredN);

          // reset intervallo e pianifica prossimo controllo
          intervalStart = nextControl;
          intervalArr   = 0;
          intervalRej   = 0;
          intervalIdle  = 0.0;

          nextControl += controlSec;
        }

        // Avanza fino all’arrivo
        Step s = advanceModelAndIntegrateIdle(model, rng, clock, ev.time, csvDebug, debugLog < DEBUG_MAX);
        idleSum      += s.idleArea;
        intervalIdle += s.idleArea;
        simTime      += s.dt;
        clock         = ev.time;

        // Inject arrivo: ritorna la fase (1..4) se accettato, 0 se rejection
        int acceptedPhase = injectRequest(model, ev.cls, rng);
        if (acceptedPhase == 0) {
          totalRejects++;
          intervalRej++;
        }
        intervalArr++;

        // Log per primi 20 eventi (stato completo all’arrivo)
        if (debugLog < DEBUG_MAX) {
          int pool = getTokens(model,"Pool");
          int f1   = getTokens(model,"Ph1");
          int f2   = getTokens(model,"Ph2");
          int f3   = getTokens(model,"Ph3");
          int f4   = getTokens(model,"Ph4");
          if (acceptedPhase > 0) {
            writeDebug(csvDebug, ev.time,
              String.format(Locale.US,
                "arrivo classe=%d: accettato in Ph%d; stato corrente: Pool=%d, Ph1=%d, Ph2=%d, Ph3=%d, Ph4=%d",
                ev.cls, acceptedPhase, pool, f1, f2, f3, f4));
          } else {
            writeDebug(csvDebug, ev.time,
              String.format(Locale.US,
                "arrivo classe=%d: RIFIUTATO; stato corrente: Pool=%d, Ph1=%d, Ph2=%d, Ph3=%d, Ph4=%d",
                ev.cls, pool, f1, f2, f3, f4));
          }
          debugLog++;
          if (debugLog == DEBUG_MAX) {
            writeDebug(csvDebug, ev.time, "== STOP DEBUG: raggiunti 20 eventi ==");
          }
        }

        if ((i+1) % LOG_EVERY == 0) {
          System.out.printf(Locale.US,
              "[progress] %d/%d  t=%.3f  rej=%d  Pool=%d  busy=%d  mode=%s  targetTot=%d%n",
              (i+1), arrivals.size(), ev.time, totalRejects,
              getTokens(model,"Pool"), countBusy(model), currentMode, lastTargetTot);
        }
      }

      // Chiudo ultimo intervallo
      double lastT = arrivals.get(arrivals.size()-1).time;
      int poolEnd  = getTokens(model,"Pool");
      writeIntervalRow(csvIntervals, intervalStart, lastT,
          poolEnd, lastTargetTot, lastApplyTime,
          intervalArr, intervalRej, intervalIdle,
          lastPredReject, lastPredN);

      // Metriche complessive
      double idleMean = (simTime > 0) ? (idleSum / simTime) : 0.0;
      double rejRate  = (double) totalRejects / arrivals.size();

      System.out.println();
      System.out.println("== RISULTATI TIMESERIES ==");
      System.out.println("Tempo totale simulato: " + String.format(Locale.US,"%.3f", simTime) + " s");
      System.out.println("Rejection totali:      " + totalRejects);
      System.out.println("Rejection rate:        " + String.format(Locale.US,"%.6f", rejRate));
      System.out.println("Idle medio (Pool):     " + String.format(Locale.US,"%.3f", idleMean));

      csvSummary.printf(Locale.US, "%.3f,%d,%.6f,%.3f%n", simTime, totalRejects, rejRate, idleMean);
    }

    System.out.println("CSV scritto: " + summaryFile);
    System.out.println("CSV per intervalli: " + intervalsFile);
    System.out.println("CSV debug: timeseries_debug.csv");
  }

  //                    Controller

  /**
   * Sceglie il totale minimo (busy+pool) tale che, simulando i prossimi
   * 'horizon' secondi con totale costante, la rejection prevista <= SLO_REJECT.
   */
  static int chooseMinReplicas(
      ModelOris2_fase4.GspnModel model,
      double now,
      double horizon,
      List<Arrival> allArrivals,
      int currentIdx,
      Random rng
  ){
    int poolNow = getTokens(model,"Pool");
    int f1 = getTokens(model,"Ph1"), f2 = getTokens(model,"Ph2"),
        f3 = getTokens(model,"Ph3"), f4 = getTokens(model,"Ph4");
    int busyNow = f1 + f2 + f3 + f4;

    double end = now + horizon;
    List<Arrival> future = sliceArrivals(allArrivals, now, end, currentIdx);
    int n = future.size();
    if (n == 0) {
      lastPredN      = 0;
      lastPredReject = 0.0;
      return Math.max(POOL_MIN, busyNow);
    }

    EntryProb entryProb = readEntryProbs(model);

    int lowerTot = Math.max(busyNow, POOL_MIN);
    int upperTot = Math.max(lowerTot, busyNow + POOL_MAX);

    for (int targetTot = lowerTot; targetTot <= upperTot; targetTot++) {
      TempState s = new TempState(now, poolNow, f1, f2, f3, f4);
      s.pool = Math.max(0, targetTot - s.busy());
      int rej = simulateHorizon(s, future, targetTot, entryProb, rng);
      double rate = (double) rej / n;
      if (rate <= SLO_REJECT) {
        lastPredN      = n;
        lastPredReject = rate;
        return targetTot;
      }
    }

    lastPredN      = n;
    lastPredReject = 1.0;
    return upperTot;
  }

  static int simulateHorizon(
      TempState s,
      List<Arrival> future,
      int targetTot,
      EntryProb prob,
      Random rng
  ){
    int rejects = 0;
    for (Arrival a : future) {
      advanceTempByExponentials(s, a.time - s.time, rng);

      int needPool = Math.max(0, targetTot - s.busy());
      s.pool = Math.min(POOL_MAX, needPool);

      double[] p = (a.cls == 1) ? prob.c1 : (a.cls == 2) ? prob.c2 : prob.c3;
      if (s.pool <= 0) {
        rejects++;
      } else {
        s.pool--;
        int ph = pickPhaseIndex(p, rng);
        if      (ph == 1) s.ph1++;
        else if (ph == 2) s.ph2++;
        else if (ph == 3) s.ph3++;
        else              s.ph4++;
      }
      s.time = a.time;
    }
    return rejects;
  }

  //                     Snapshot temporaneo (predizione)

  static final class TempState {
    double time;
    int pool;
    int ph1, ph2, ph3, ph4;
    TempState(double t, int pool, int ph1, int ph2, int ph3, int ph4){
      this.time=t; this.pool=pool; this.ph1=ph1; this.ph2=ph2; this.ph3=ph3; this.ph4=ph4;
    }
    int busy(){ return ph1+ph2+ph3+ph4; }
  }

  static final class EntryProb {
    final double[] c1 = new double[5];
    final double[] c2 = new double[5];
    final double[] c3 = new double[5];
  }

  static EntryProb readEntryProbs(ModelOris2_fase4.GspnModel model){
    EntryProb ep = new EntryProb();
    double[] p1 = classEntryProbs(model, 1);
    double[] p2 = classEntryProbs(model, 2);
    double[] p3 = classEntryProbs(model, 3);
    System.arraycopy(p1,0,ep.c1,0,5);
    System.arraycopy(p2,0,ep.c2,0,5);
    System.arraycopy(p3,0,ep.c3,0,5);
    return ep;
  }

  static int pickPhaseIndex(double[] probs, Random rng){
    double u = rng.nextDouble();
    double c1 = probs[1];
    double c2 = c1 + probs[2];
    double c3 = c2 + probs[3];
    if (u < c1) return 1;
    if (u < c2) return 2;
    if (u < c3) return 3;
    return 4;
  }

  static void advanceTempByExponentials(TempState s, double dt, Random rng){
    if (dt <= 0) return;
    double t = 0.0;
    final int bph = Math.max(1, (bphDivCached != null ? bphDivCached : 10));

    while (true) {
      double r1 = (s.ph1 > 0) ? (1.0 * s.ph1) / bph : 0.0;
      double r2 = (s.ph2 > 0) ? (2.0 * s.ph2) / bph : 0.0;
      double r3 = (s.ph3 > 0) ? (3.0 * s.ph3) / bph : 0.0;
      double r4 = (s.ph4 > 0) ? (4.0 * s.ph4) / bph : 0.0;
      double R = r1 + r2 + r3 + r4;
      if (R <= 0.0) { s.time += (dt - t); return; }

      double tau = drawExp(R, rng);
      if (t + tau >= dt) { s.time += (dt - t); return; }

      t += tau;
      int ev = pickWeightedIndex(new double[]{r1, r2, r3, r4}, rng);
      if (ev == 0 && s.ph1 > 0) { s.ph1--; s.ph2++; }
      else if (ev == 1 && s.ph2 > 0) { s.ph2--; s.ph3++; }
      else if (ev == 2 && s.ph3 > 0) { s.ph3--; s.ph4++; }
      else if (ev == 3 && s.ph4 > 0) { s.ph4--; s.pool++; }
    }
  }

  //               Avanzamento reale + integrazione idle

  static final class Step {
    final double idleArea;
    final double dt;
    Step(double idleArea, double dt){ this.idleArea = idleArea; this.dt = dt; }
  }

  static Step advanceModelAndIntegrateIdle(
      ModelOris2_fase4.GspnModel model, Random rng,
      double t0, double t1,
      PrintWriter debugLog, boolean debugOn
  ){
    if (t1 <= t0) return new Step(0.0, 0.0);

    double t = t0;
    double idleArea = 0.0;
    int pool = getTokens(model,"Pool");
    final int bph = Math.max(1, getTokens(model,"BphDiv"));

    while (true) {
      int ph1 = getTokens(model,"Ph1");
      int ph2 = getTokens(model,"Ph2");
      int ph3 = getTokens(model,"Ph3");
      int ph4 = getTokens(model,"Ph4");

      double r1 = (ph1 > 0) ? (1.0 * ph1) / bph : 0.0;
      double r2 = (ph2 > 0) ? (2.0 * ph2) / bph : 0.0;
      double r3 = (ph3 > 0) ? (3.0 * ph3) / bph : 0.0;
      double r4 = (ph4 > 0) ? (4.0 * ph4) / bph : 0.0;
      double R = r1 + r2 + r3 + r4;

      if (R <= 0.0) { // nessun completamento possibile
        idleArea += pool * (t1 - t);
        break;
      }

      double tau = drawExp(R, rng);
      if (t + tau >= t1) {
        idleArea += pool * (t1 - t);
        break;
      }

      // Integro fino al prossimo completamento
      idleArea += pool * tau;
      t += tau;

      int ev = pickWeightedIndex(new double[]{r1, r2, r3, r4}, rng);
      if (ev == 0 && ph1 > 0) {
        setTokens(model,"Ph1", ph1 - 1); setTokens(model,"Ph2", ph2 + 1);
        if (debugOn) writeDebug(debugLog, t, "movimento: token spostato da Ph1 a Ph2");
      } else if (ev == 1 && ph2 > 0) {
        setTokens(model,"Ph2", ph2 - 1); setTokens(model,"Ph3", ph3 + 1);
        if (debugOn) writeDebug(debugLog, t, "movimento: token spostato da Ph2 a Ph3");
      } else if (ev == 2 && ph3 > 0) {
        setTokens(model,"Ph3", ph3 - 1); setTokens(model,"Ph4", ph4 + 1);
        if (debugOn) writeDebug(debugLog, t, "movimento: token spostato da Ph3 a Ph4");
      } else if (ev == 3 && ph4 > 0) {
        setTokens(model,"Ph4", ph4 - 1); setTokens(model,"Pool", pool + 1);
        pool = pool + 1; // aggiorno locale
        if (debugOn) writeDebug(debugLog, t, "movimento: token completato da Ph4 a Pool");
      }
    }

    return new Step(idleArea, t1 - t0);
  }

  //                     Inject / probabilità ingresso

  /**
   * Prova a inserire una richiesta.
   * @return fase di ingresso (1..4) se accettata, 0 se rifiutata
   */
  static int injectRequest(ModelOris2_fase4.GspnModel model, int cls, Random rng){
    int pool = getTokens(model,"Pool");
    if (pool <= 0) return 0; // rejection

    int phase = chooseEntryPhase(model, cls, rng);
    String dest = (phase==1)?"Ph1":(phase==2)?"Ph2":(phase==3)?"Ph3":"Ph4";

    setTokens(model,"Pool", pool-1);
    setTokens(model, dest, getTokens(model,dest)+1);
    return phase;
  }

  static int chooseEntryPhase(ModelOris2_fase4.GspnModel model, int cls, Random rng){
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

  static double[] classEntryProbs(ModelOris2_fase4.GspnModel model, int cls) {
    String p1="W11",p2="W12",p3="W13",p4="W14";
    if (cls==2){ p1="W21"; p2="W22"; p3="W23"; p4="W24"; }
    if (cls==3){ p1="W31"; p2="W32"; p3="W33"; p4="W34"; }
    int w1=getTokens(model,p1), w2=getTokens(model,p2), w3=getTokens(model,p3), w4=getTokens(model,p4);
    double sum = Math.max(1, w1+w2+w3+w4);
    return new double[]{0.0, w1/sum, w2/sum, w3/sum, w4/sum};
  }

  //                           I/O + utilità

  static List<Arrival> readArrivalsCsv(String file) throws Exception {
    List<Arrival> out = new ArrayList<>();
    try (BufferedReader br = new BufferedReader(new FileReader(file))) {
      String line = br.readLine(); // header
      while ((line = br.readLine()) != null) {
        String[] s = line.trim().split(",");
        if (s.length < 2) continue;
        double t = Double.parseDouble(s[0]);
        int    c = Integer.parseInt(s[1]);
        out.add(new Arrival(t, c));
      }
    }
    out.sort(Comparator.comparingDouble(a -> a.time));
    return out;
  }

  static List<Arrival> sliceArrivals(List<Arrival> arr, double from, double to, int startIdx){
    List<Arrival> out = new ArrayList<>();
    for (int i = Math.max(0, startIdx); i < arr.size(); i++){
      Arrival a = arr.get(i);
      if (a.time < from) continue;
      if (a.time >= to) break;
      out.add(a);
    }
    return out;
  }

  static void writeIntervalRow(PrintWriter csv,
                               double tStart, double tEnd,
                               int poolNow, int targetTot, double effChangeTime,
                               int arrivals, int rejects, double idleIntegralInterval,
                               double predRejAtTarget, int predN) {
    if (tEnd <= tStart + 1e-12) return;
    double dt = tEnd - tStart;
    double idleMeanInt = (dt > 0) ? (idleIntegralInterval / dt) : 0.0;
    double rejRateInt  = (arrivals > 0) ? (double) rejects / arrivals : 0.0;

    csv.printf(Locale.US,
        "%.3f,%.3f,%d,%d,%.3f,%d,%d,%.6f,%.3f,%.6f,%d%n",
        tStart, tEnd, poolNow, targetTot, effChangeTime,
        arrivals, rejects, rejRateInt, idleMeanInt,
        predRejAtTarget, predN);
  }

  static void disableAutomaticArrivals(ModelOris2_fase4.GspnModel model){
    setTokens(model,"Rate1",0);
    setTokens(model,"Rate2",0);
    setTokens(model,"Rate3",0);
    setTokens(model,"RateDiv",1);
    bphDivCached = getTokens(model,"BphDiv");
  }

  static double ceilToStep(double t, double step){
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

  static double drawExp(double rate, Random rng){
    double u = Math.max(1e-12, 1.0 - rng.nextDouble());
    return -Math.log(u) / rate;
  }

  static int pickWeightedIndex(double[] w, Random rng){
    double sum = 0.0; for (double v : w) sum += v;
    if (sum <= 0.0) return 0;
    double u = rng.nextDouble() * sum, acc = 0.0;
    for (int i=0;i<w.length;i++){ acc += w[i]; if (u <= acc) return i; }
    return w.length - 1;
  }

  // Debug CSV “time|message”
  static void writeDebug(PrintWriter dbg, double t, String msg) {
    if (dbg != null) dbg.printf(Locale.US, "%.3f|%s%n", t, msg);
  }
}
