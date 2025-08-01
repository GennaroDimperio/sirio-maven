package com.example;

import java.math.BigDecimal;
import java.util.Arrays;

import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;
import org.oristool.petrinet.Place;
import org.oristool.petrinet.Transition;

public class ModelOris2 {

    private final int[] arrivalRates;            
    private final int   poolSize;                
    private final int   numPh;                   
    private final int[][] weights;               
    private final int   rateDiv;                 
    private final int   bphDiv;                  
    private final int   wDiv;                    

    public ModelOris2(int[] arrivalRates,
                      int   poolSize,
                      int   numPh,
                      int[][] weights,
                      int   rateDiv,
                      int   bphDiv,
                      int   wDiv) {
        this.arrivalRates = arrivalRates;
        this.poolSize     = poolSize;
        this.numPh        = numPh;
        this.weights      = weights;
        this.rateDiv      = rateDiv;
        this.bphDiv       = bphDiv;
        this.wDiv         = wDiv;
    }

    public PetriNet build() {

        PetriNet net = new PetriNet();

        Place pool = net.addPlace("Pool");

        Place[] px  = new Place[3];  // P1-P3
        Place[] ax  = new Place[3];  // A1-A3
        for (int i = 0; i < 3; i++) {
            px[i] = net.addPlace("P" + (i + 1));
            ax[i] = net.addPlace("A" + (i + 1));

        }

        Place[] phx = new Place[numPh];          // Ph1-Ph4
        for (int j = 0; j < numPh; j++)
            phx[j] = net.addPlace("Ph" + (j + 1));

        for (int i = 0; i < 3; i++) {
            Transition rel = net.addTransition("release" + (i + 1));
            BigDecimal λ   = BigDecimal.valueOf(arrivalRates[i])
                                        .divide(BigDecimal.valueOf(rateDiv));
            rel.addFeature(StochasticTransitionFeature
                        .newExponentialInstance(λ, MarkingExpr.from("1", net)));

            net.addPrecondition(px[i], rel);   
            net.addPrecondition(pool , rel);   
            net.addPostcondition(rel, ax[i]);
            net.addPostcondition(rel, px[i]);

        }

        for (int i = 0; i < 3; i++)
            for (int j = 0; j < numPh; j++) {
                double w    = weights[i][j] / (double) wDiv;
                double rate = (w == 0) ? 1e-12 : w;

                Transition tij = net.addTransition("t" + (i + 1) + (j + 1));
                tij.addFeature(StochasticTransitionFeature
                            .newExponentialInstance(
                                BigDecimal.valueOf(rate),
                                MarkingExpr.from("1", net)));

                net.addPrecondition(ax[i], tij);      
                net.addPostcondition(tij , phx[j]);  
            }

        for (int j = 0; j < numPh; j++) {
            Transition svc = net.addTransition("t" + (j + 1));
            BigDecimal μ   = BigDecimal.valueOf(j + 1)
                                    .divide(BigDecimal.valueOf(bphDiv));
            svc.addFeature(StochasticTransitionFeature
                        .newExponentialInstance(
                            μ,
                            MarkingExpr.from("Ph" + (j + 1), net)));

            net.addPrecondition(phx[j], svc);                
            Place dst = (j < numPh - 1) ? phx[j + 1] : pool;
            net.addPostcondition(svc, dst);
        }

        return net;
    }

    public Marking buildInitialMarking(PetriNet net) {
        Marking m = new Marking();
        m.setTokens(net.getPlace("Pool"), poolSize);
        m.setTokens(net.getPlace("P1"),   1);
        m.setTokens(net.getPlace("P2"),   1);
        m.setTokens(net.getPlace("P3"),   1);
        return m;
    }

    // third model
    public static PetriNet getThirdModel(double[] probs){
        PetriNet net=new PetriNet(); Place init=net.addPlace("Init");
        Place[] tgt={net.addPlace("Pool"),net.addPlace("Ph1"),net.addPlace("Ph2"),net.addPlace("Ph3"),net.addPlace("Ph4")};
        String[] names={"Pool","Ph1","Ph2","Ph3","Ph4"};
        double tot=Arrays.stream(probs).sum();
        for(int i=0;i<5;i++){
            double p=(tot==0)?0:probs[i]/tot; double rate=(p==0)?1e-12:p;
            Transition t=net.addTransition("tInit_"+names[i]);
            t.addFeature(StochasticTransitionFeature.newExponentialInstance(BigDecimal.valueOf(rate),MarkingExpr.from("1",net)));
            net.addPrecondition(init,t); net.addPostcondition(t,tgt[i]); }
        return net; }

    public static Marking getThirdModelInitialMarking(PetriNet net,
                                                  double[] probs,
                                                  int lowPool,
                                                  int deltaPool) {
                Marking m = new Marking();
                String[] n = {"Pool","Ph1","Ph2","Ph3","Ph4"};

            double tot = Arrays.stream(probs).sum();
            int[] tok = new int[5];
            int assigned = 0;
            for (int i = 0; i < 5; i++) {
                double p = (tot == 0) ? 0 : probs[i] / tot;
                tok[i]   = (p > 0) ? Math.max(1, (int) Math.round(p * lowPool)) : 0;
                assigned += tok[i];
            }

            int missing = lowPool - assigned;  
                while (missing != 0) {
                    int idx = missing > 0
                         ? argMax(tok)        
                         : argMinPositive(tok); 
                        tok[idx] += (missing > 0) ? 1 : -1;
                        missing  += (missing > 0) ? -1 : 1;
            }

    for (int i = 0; i < 5; i++) {
        m.setTokens(net.getPlace(n[i]), tok[i]);
    }

    int poolTok = m.getTokens(net.getPlace("Pool"));
    m.setTokens(net.getPlace("Pool"), poolTok + deltaPool);
    m.setTokens(net.getPlace("Init"), deltaPool);

    return m;
}

private static int argMax(int[] v) {
    int idx = 0;
    for (int i = 1; i < v.length; i++) if (v[i] > v[idx]) idx = i;
    return idx;
}
private static int argMinPositive(int[] v) {
    int idx = -1;
    for (int i = 0; i < v.length; i++)
        if (v[i] > 0 && (idx == -1 || v[i] < v[idx])) idx = i;
    return idx;
}
}
