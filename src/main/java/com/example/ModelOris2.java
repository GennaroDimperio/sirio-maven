package com.example;

import java.math.BigDecimal;

import org.oristool.models.pn.Priority;
import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;
import org.oristool.petrinet.Place;
import org.oristool.petrinet.Transition;

public class ModelOris2 {

    private final int[]   arrival;        
    private final int     poolSize;
    private final int     numPh;
    private final int[][] W;
    private final int     rateDiv, bphDiv, wDiv;

    public ModelOris2(int[] arrivalRates, int poolSize,
                      int numPh, int[][] W,
                      int rateDiv, int bphDiv, int wDiv) {

        this.arrival  = arrivalRates;
        this.poolSize = poolSize;
        this.numPh    = numPh;
        this.W        = W;
        this.rateDiv  = rateDiv;
        this.bphDiv   = bphDiv;
        this.wDiv     = wDiv;
    }

    public PetriNet build() {

        PetriNet net = new PetriNet();
        Place pool = net.addPlace("Pool");

        
        Place[] P = new Place[3];
        Place[] A = new Place[3];
        for (int i = 0; i < 3; i++) {
            P[i] = net.addPlace("P"+(i+1));
            A[i] = net.addPlace("A"+(i+1));
        }

        // Ph
        Place[] Ph = new Place[numPh];
        for (int j = 0; j < numPh; j++)
            Ph[j] = net.addPlace("Ph"+(j+1));

        net.addPlace("RateDiv");
        net.addPlace("BphDiv");
        net.addPlace("WDiv");

        for (int i = 0; i < 3; i++)                    
            net.addPlace("Rate"+(i+1));

        for (int i = 0; i < 3; i++)                  
            for (int j = 0; j < numPh; j++)
                net.addPlace("W"+(i+1)+(j+1));

        for (int i = 0; i < 3; i++) {
            Transition r = net.addTransition("release"+(i+1));
            r.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.ONE,
                    MarkingExpr.from("Rate"+(i+1)+"/RateDiv", net)));
            net.addPrecondition(pool, r);
            net.addPrecondition(P[i], r);
            net.addPostcondition(r, A[i]);
            net.addPostcondition(r, P[i]);
        }

        for (int i = 0; i < 3; i++)
            for (int j = 0; j < numPh; j++) {
                double rate = W[i][j] / (double) wDiv;
                Transition d = net.addTransition("d"+(i+1)+(j+1));
                d.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                        BigDecimal.ZERO,
                        MarkingExpr.from(Double.toString(rate), net)));
                d.addFeature(new Priority(0));
                net.addPrecondition(A[i], d);
                net.addPostcondition(d, Ph[j]);
            }

        
        for (int j = 0; j < numPh; j++) {
            Transition s = net.addTransition("svc"+(j+1));
            s.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.ONE,
                    MarkingExpr.from((j+1)+"*Ph"+(j+1)+"/BphDiv", net)));
            net.addPrecondition(Ph[j], s);
            net.addPostcondition(s, (j < numPh-1) ? Ph[j+1] : pool);
        }
        return net;
    }

    public Marking buildInitialMarking(PetriNet net) {
        Marking m = new Marking();
        m.setTokens(net.getPlace("RateDiv"), rateDiv);
        m.setTokens(net.getPlace("BphDiv"),  bphDiv);
        m.setTokens(net.getPlace("WDiv"),    wDiv);

        for (int i = 0; i < 3; i++)
            m.setTokens(net.getPlace("Rate"+(i+1)), arrival[i]);

        for (int i = 0; i < 3; i++)
            for (int j = 0; j < numPh; j++)
                m.setTokens(net.getPlace("W"+(i+1)+(j+1)), W[i][j]);

        /* operativi */
        m.setTokens(net.getPlace("Pool"), poolSize);
        m.setTokens(net.getPlace("P1"), 1);
        m.setTokens(net.getPlace("P2"), 1);
        m.setTokens(net.getPlace("P3"), 1);
        return m;
    }

    public static PetriNet getThirdModel(double[] wLow,      // pesi Init
                                         int[]    rateLow,   // {2,3,1}
                                         int[]    rateHigh,  // {8,12,4}
                                         double   tSwitch) {

        PetriNet net = new PetriNet();

        Place pool = net.addPlace("Pool");
        Place[] P  = { net.addPlace("P1"), net.addPlace("P2"), net.addPlace("P3") };
        Place[] A  = { net.addPlace("A1"), net.addPlace("A2"), net.addPlace("A3") };
        Place[] Ph = { net.addPlace("Ph1"), net.addPlace("Ph2"),
                       net.addPlace("Ph3"), net.addPlace("Ph4") };

        Place init = net.addPlace("Init");
        Place[] targets = { pool, Ph[0], Ph[1], Ph[2], Ph[3] };
        String[] names  = { "Pool","Ph1","Ph2","Ph3","Ph4" };
        for (int i = 0; i < targets.length; i++) {
            Transition t = net.addTransition("tInit_"+names[i]);
            double w = wLow[i] <= 0 ? 1e-12 : wLow[i];
            t.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                    BigDecimal.ZERO,
                    MarkingExpr.from(Double.toString(w), net)));
            net.addPrecondition(init, t);          // Init si scarica
            net.addPostcondition(t, targets[i]);
        }

        Place lowGate  = net.addPlace("PhaseLow");
        Place highGate = net.addPlace("PhaseHigh");
        Transition sw  = net.addTransition("tSwitchPhase");
        sw.addFeature(StochasticTransitionFeature.newExponentialInstance(
                BigDecimal.valueOf(1.0 / tSwitch), MarkingExpr.from("1", net)));
        net.addPrecondition(lowGate, sw);
        net.addPostcondition(sw, highGate);

        for (int i = 0; i < 3; i++) {
            addRelease(net, "Low",  i, rateLow[i],  pool, P[i], A[i], lowGate);
            addRelease(net, "High", i, rateHigh[i], pool, P[i], A[i], highGate);
        }

        int[][] Wbase = { {1,1,1,1}, {1,2,3,4}, {4,3,2,1} };
        int wDiv = 20;
        for (int i = 0; i < 3; i++)
            for (int j = 0; j < 4; j++) {
                double rate = Wbase[i][j] / (double) wDiv;
                Transition d = net.addTransition("d"+(i+1)+(j+1));
                d.addFeature(StochasticTransitionFeature.newDeterministicInstance(
                        BigDecimal.ZERO,
                        MarkingExpr.from(Double.toString(rate), net)));
                d.addFeature(new Priority(0));
                net.addPrecondition(A[i], d);
                net.addPostcondition(d, Ph[j]);
            }

        int bphDiv = 5;
        for (int j = 0; j < 4; j++) {
            Transition s = net.addTransition("svc"+(j+1));
            s.addFeature(StochasticTransitionFeature.newExponentialInstance(
                    BigDecimal.ONE,
                    MarkingExpr.from((j+1)+"*Ph"+(j+1)+"/"+bphDiv, net)));
            net.addPrecondition(Ph[j], s);
            net.addPostcondition(s, (j < 3) ? Ph[j+1] : pool);
        }

        return net;
    }

    private static void addRelease(PetriNet net, String tag, int idx, int λ,
                                   Place pool, Place Pi, Place Ai, Place gate) {

        Transition r = net.addTransition("release"+(idx+1)+"_"+tag);
        r.addFeature(StochasticTransitionFeature.newExponentialInstance(
                BigDecimal.valueOf(λ), MarkingExpr.from("1", net)));

        net.addPrecondition(pool, r);
        net.addPrecondition(Pi,  r);
        net.addPrecondition(gate, r);

        net.addPostcondition(r, Ai);   
        net.addPostcondition(r, Pi);   
        net.addPostcondition(r, gate);
    }

    public static Marking getThirdModelInitialMarking(PetriNet net,
                                                      int lowPool,
                                                      int deltaPool) {

        Marking m = new Marking();
        m.setTokens(net.getPlace("PhaseLow"), 1);  
        m.setTokens(net.getPlace("Init"),     lowPool);
        m.setTokens(net.getPlace("Pool"),     deltaPool);
        m.setTokens(net.getPlace("P1"), 1);
        m.setTokens(net.getPlace("P2"), 1);
        m.setTokens(net.getPlace("P3"), 1);

        return m;
    }
}
