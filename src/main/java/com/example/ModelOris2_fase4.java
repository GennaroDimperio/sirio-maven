package com.example;

import java.math.BigDecimal;

import org.oristool.models.pn.Priority;
import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.EnablingFunction;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;
import org.oristool.petrinet.Place;
import org.oristool.petrinet.Transition;

public class ModelOris2_fase4 {

  // Contenitore rete + marcatura. 
  public static class GspnModel {
    public final PetriNet net = new PetriNet();
    public final Marking  marking = new Marking();
  }

  // Costruisce il modello (derivato da ORIS).
  public static GspnModel build() {
    GspnModel m = new GspnModel();
    PetriNet net = m.net;
    Marking  marking = m.marking;

    // Places
    Place A1 = net.addPlace("A1");
    Place A2 = net.addPlace("A2");
    Place A3 = net.addPlace("A3");
    Place BphDiv = net.addPlace("BphDiv");
    Place MixVariationRateDiv = net.addPlace("MixVariationRateDiv");
    Place ModRateDiv = net.addPlace("ModRateDiv");
    Place P1 = net.addPlace("P1");
    Place P2 = net.addPlace("P2");
    Place P3 = net.addPlace("P3");
    Place Ph1 = net.addPlace("Ph1");
    Place Ph2 = net.addPlace("Ph2");
    Place Ph3 = net.addPlace("Ph3");
    Place Ph4 = net.addPlace("Ph4");
    Place Pool = net.addPlace("Pool");
    Place Rate1 = net.addPlace("Rate1");
    Place Rate2 = net.addPlace("Rate2");
    Place Rate3 = net.addPlace("Rate3");
    Place RateDiv = net.addPlace("RateDiv");
    Place W11 = net.addPlace("W11");
    Place W12 = net.addPlace("W12");
    Place W13 = net.addPlace("W13");
    Place W14 = net.addPlace("W14");
    Place W21 = net.addPlace("W21");
    Place W22 = net.addPlace("W22");
    Place W23 = net.addPlace("W23");
    Place W24 = net.addPlace("W24");
    Place W31 = net.addPlace("W31");
    Place W32 = net.addPlace("W32");
    Place W33 = net.addPlace("W33");
    Place W34 = net.addPlace("W34");
    Place WorkloadDown = net.addPlace("WorkloadDown");
    Place WorkloadStableDown = net.addPlace("WorkloadStableDown");
    Place WorkloadStableUp = net.addPlace("WorkloadStableUp");
    Place WorkloadUp = net.addPlace("WorkloadUp");

    // Transitions
    Transition down21 = net.addTransition("down21");
    Transition down32 = net.addTransition("down32");
    Transition release1 = net.addTransition("release1");
    Transition release2 = net.addTransition("release2");
    Transition release3 = net.addTransition("release3");
    Transition t1 = net.addTransition("t1");
    Transition t11 = net.addTransition("t11");
    Transition t12 = net.addTransition("t12");
    Transition t13 = net.addTransition("t13");
    Transition t14 = net.addTransition("t14");
    Transition t2 = net.addTransition("t2");
    Transition t21 = net.addTransition("t21");
    Transition t22 = net.addTransition("t22");
    Transition t23 = net.addTransition("t23");
    Transition t24 = net.addTransition("t24");
    Transition t3 = net.addTransition("t3");
    Transition t31 = net.addTransition("t31");
    Transition t32 = net.addTransition("t32");
    Transition t33 = net.addTransition("t33");
    Transition t34 = net.addTransition("t34");
    Transition t35 = net.addTransition("t35");
    Transition t36 = net.addTransition("t36");
    Transition t37 = net.addTransition("t37");
    Transition t38 = net.addTransition("t38");
    Transition t4 = net.addTransition("t4");
    Transition up12 = net.addTransition("up12");
    Transition up23 = net.addTransition("up23");

    // Arcs
    net.addPostcondition(t22, Ph2);
    net.addPrecondition(P2, release2);
    net.addPostcondition(t12, Ph2);
    net.addPostcondition(t36, WorkloadDown);
    net.addPostcondition(t35, WorkloadStableUp);
    net.addPostcondition(t23, Ph3);
    net.addPostcondition(t24, Ph4);
    net.addPostcondition(release2, A2);
    net.addPrecondition(Pool, release3);
    net.addPostcondition(release1, A1);
    net.addPrecondition(A3, t32);
    net.addPrecondition(A1, t14);
    net.addPostcondition(t11, Ph1);
    net.addPrecondition(A2, t22);
    net.addPrecondition(A3, t33);
    net.addPostcondition(t4, Pool);
    net.addPrecondition(WorkloadUp, t35);
    net.addPostcondition(t21, Ph1);
    net.addPrecondition(Ph1, t1);
    net.addPrecondition(P3, release3);
    net.addPrecondition(Rate1, up12);
    net.addPostcondition(t34, Ph4);
    net.addPrecondition(P1, release1);
    net.addPrecondition(A3, t34);
    net.addPostcondition(down32, Rate2);
    net.addPrecondition(WorkloadDown, t38);
    net.addPrecondition(Pool, release2);
    net.addPrecondition(A1, t11);
    net.addPrecondition(A3, t31);
    net.addPostcondition(t32, Ph2);
    net.addPostcondition(release2, P2);
    net.addPostcondition(t38, WorkloadStableDown);
    net.addPostcondition(t13, Ph3);
    net.addPrecondition(Rate2, down21);
    net.addPrecondition(Pool, release1);
    net.addPostcondition(t33, Ph3);
    net.addPostcondition(up23, Rate3);
    net.addPrecondition(Rate3, down32);
    net.addPostcondition(t2, Ph3);
    net.addPrecondition(Rate2, up23);
    net.addPrecondition(Ph3, t3);
    net.addPrecondition(A1, t12);
    net.addPostcondition(release3, A3);
    net.addPrecondition(A2, t24);
    net.addPostcondition(release1, P1);
    net.addPrecondition(A1, t13);
    net.addPostcondition(release3, P3);
    net.addPrecondition(A2, t21);
    net.addPrecondition(Ph4, t4);
    net.addPostcondition(down21, Rate1);
    net.addPrecondition(A2, t23);
    net.addPostcondition(t37, WorkloadUp);
    net.addPostcondition(up12, Rate2);
    net.addPostcondition(t14, Ph4);
    net.addPostcondition(t1, Ph2);
    net.addPostcondition(t3, Ph4);
    net.addPrecondition(Ph2, t2);
    net.addPrecondition(WorkloadStableDown, t37);
    net.addPostcondition(t31, Ph1);
    net.addPrecondition(WorkloadStableUp, t36);

    // Marking iniziale
    marking.setTokens(A1, 0);
    marking.setTokens(A2, 0);
    marking.setTokens(A3, 0);
    marking.setTokens(BphDiv, 10);
    marking.setTokens(MixVariationRateDiv, 10);
    marking.setTokens(ModRateDiv, 200);
    marking.setTokens(P1, 1);
    marking.setTokens(P2, 1);
    marking.setTokens(P3, 1);
    marking.setTokens(Ph1, 0);
    marking.setTokens(Ph2, 0);
    marking.setTokens(Ph3, 0);
    marking.setTokens(Ph4, 0);
    marking.setTokens(Pool, 8);
    marking.setTokens(Rate1, 5);
    marking.setTokens(Rate2, 3);
    marking.setTokens(Rate3, 2);
    marking.setTokens(RateDiv, 10);
    // pesi (divisore W rimosso)
    marking.setTokens(W11, 1); marking.setTokens(W12, 1);
    marking.setTokens(W13, 1); marking.setTokens(W14, 1);
    marking.setTokens(W21, 1); marking.setTokens(W22, 2);
    marking.setTokens(W23, 3); marking.setTokens(W24, 4);
    marking.setTokens(W31, 4); marking.setTokens(W32, 3);
    marking.setTokens(W33, 2); marking.setTokens(W34, 1);
    marking.setTokens(WorkloadDown, 0);
    marking.setTokens(WorkloadStableDown, 1);
    marking.setTokens(WorkloadStableUp, 0);
    marking.setTokens(WorkloadUp, 0);

    // Feature/transizioni
    down21.addFeature(new EnablingFunction("WorkloadDown>0 && Rate2>1"));
    down21.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1/MixVariationRateDiv", net)));
    down32.addFeature(new EnablingFunction("WorkloadDown>0 && Rate3>1"));
    down32.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1/MixVariationRateDiv", net)));

    release1.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("Rate1/RateDiv", net)));
    release2.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("Rate2/RateDiv", net)));
    release3.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("Rate3/RateDiv", net)));

    t1.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1*Ph1/BphDiv", net)));
    t11.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W11", net))); t11.addFeature(new Priority(0));
    t12.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W12", net))); t12.addFeature(new Priority(0));
    t13.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W13", net))); t13.addFeature(new Priority(0));
    t14.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W14", net))); t14.addFeature(new Priority(0));

    t2.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("2*Ph2/BphDiv", net)));
    t21.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W21", net))); t21.addFeature(new Priority(0));
    t22.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W22", net))); t22.addFeature(new Priority(0));
    t23.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W23", net))); t23.addFeature(new Priority(0));
    t24.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W24", net))); t24.addFeature(new Priority(0));

    t3.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("3*Ph3/BphDiv", net)));
    t31.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W31", net))); t31.addFeature(new Priority(0));
    t32.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W32", net))); t32.addFeature(new Priority(0));
    t33.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W33", net))); t33.addFeature(new Priority(0));
    t34.addFeature(StochasticTransitionFeature.newDeterministicInstance(new BigDecimal("0"), MarkingExpr.from("W34", net))); t34.addFeature(new Priority(0));

    t35.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("5/ModRateDiv", net)));
    t36.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1/ModRateDiv", net)));
    t37.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1/ModRateDiv", net)));
    t38.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("5/ModRateDiv", net)));

    t4.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("4*Ph4/BphDiv", net)));

    up12.addFeature(new EnablingFunction("WorkloadUp>0 && Rate1>1"));
    up12.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1/MixVariationRateDiv", net)));
    up23.addFeature(new EnablingFunction("WorkloadUp>0 && Rate2>1"));
    up23.addFeature(StochasticTransitionFeature.newExponentialInstance(new BigDecimal("1"), MarkingExpr.from("1/MixVariationRateDiv", net)));

    return m;
  }

  // ---------- Setter ----------
  public static void setWeights(GspnModel m, int[] a1, int[] a2, int[] a3) {
    m.marking.setTokens(m.net.getPlace("W11"), a1[0]);
    m.marking.setTokens(m.net.getPlace("W12"), a1[1]);
    m.marking.setTokens(m.net.getPlace("W13"), a1[2]);
    m.marking.setTokens(m.net.getPlace("W14"), a1[3]);

    m.marking.setTokens(m.net.getPlace("W21"), a2[0]);
    m.marking.setTokens(m.net.getPlace("W22"), a2[1]);
    m.marking.setTokens(m.net.getPlace("W23"), a2[2]);
    m.marking.setTokens(m.net.getPlace("W24"), a2[3]);

    m.marking.setTokens(m.net.getPlace("W31"), a3[0]);
    m.marking.setTokens(m.net.getPlace("W32"), a3[1]);
    m.marking.setTokens(m.net.getPlace("W33"), a3[2]);
    m.marking.setTokens(m.net.getPlace("W34"), a3[3]);
  }
  public static void setRateDiv(GspnModel m, int rateDiv) {
    m.marking.setTokens(m.net.getPlace("RateDiv"), rateDiv);
  }
  public static void setArrivalRates(GspnModel m, int r1, int r2, int r3) {
    m.marking.setTokens(m.net.getPlace("Rate1"), r1);
    m.marking.setTokens(m.net.getPlace("Rate2"), r2);
    m.marking.setTokens(m.net.getPlace("Rate3"), r3);
  }
  public static void setPoolTokens(GspnModel m, int pool) {
    m.marking.setTokens(m.net.getPlace("Pool"), pool);
  }

  // ---------- Rewards ----------
  public static MarkingExpr rejectionExpr(GspnModel m) {
    return MarkingExpr.from("If(Pool==0,(Rate1+Rate2+Rate3)/RateDiv,0)", m.net);
  }
  public static MarkingExpr idleExpr(GspnModel m) {
    return MarkingExpr.from("Pool", m.net);
  }
  public static MarkingExpr intensityExpr(GspnModel m) {
    return MarkingExpr.from("Rate1 + 2*Rate2 + 3*Rate3", m.net);
  }
  public static MarkingExpr phaseExpr(GspnModel m) {
    return MarkingExpr.from("1*WorkloadStableDown + 2*WorkloadUp + 3*WorkloadStableUp + 2*WorkloadDown", m.net);
  }
}
