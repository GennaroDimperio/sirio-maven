package com.example;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Stima dei rate a finestra mobile:
 * - add(t, cls): registra un arrivo (t in secondi, cls in {1,2,3})
 * - estimateRatesAt(timeSec): restituisce lamba 1..3 (jobs/sec) stimati sugli ultimi W secondi.
 */
public class SlidingRateEstimator {

  public static class Rates {
    public final double lambda1, lambda2, lambda3;
    public Rates(double l1, double l2, double l3) {
      this.lambda1 = l1; this.lambda2 = l2; this.lambda3 = l3;
    }
    @Override public String toString(){
      return String.format("Rates{l1=%.3f,l2=%.3f,l3=%.3f}", lambda1,lambda2,lambda3);
    }
  }

  private static final class Event {
    final double t;
    final int cls;
    Event(double t, int cls){ this.t = t; this.cls = cls; }
  }

  private final double windowSec;
  @SuppressWarnings("unused")
  private final double stepSec;
  private final Deque<Event> q = new ArrayDeque<>();

  public SlidingRateEstimator(double windowSec, double stepSec) {
    this.windowSec = windowSec;
    this.stepSec   = stepSec;
  }

  // Registra un arrivo
  public void add(double t, int cls) {
    if (cls < 1 || cls > 3) return;
    q.addLast(new Event(t, cls));
  }

  // Stima dei rate sugli ultimi windowSec secondi
  public Rates estimateRatesAt(double timeSec) {
    // scarta tutto ciò che è più vecchio della finestra
    double cutoff = timeSec - windowSec;
    while (!q.isEmpty() && q.peekFirst().t < cutoff) {
      q.removeFirst();
    }
    int c1 = 0, c2 = 0, c3 = 0;
    for (Event e : q) {
      if (e.t <= timeSec && e.t >= cutoff) {
        if      (e.cls == 1) c1++;
        else if (e.cls == 2) c2++;
        else if (e.cls == 3) c3++;
      }
    }
    double w = Math.max(1e-9, windowSec);
    return new Rates(c1 / w, c2 / w, c3 / w);
  }
}
