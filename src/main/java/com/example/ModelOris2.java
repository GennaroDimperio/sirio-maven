package com.example;

import java.math.BigDecimal;

import org.oristool.models.stpn.MarkingExpr;
import org.oristool.models.stpn.trees.StochasticTransitionFeature;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;
import org.oristool.petrinet.Place;
import org.oristool.petrinet.Transition;

public class ModelOris2 {

    // Parametri del modello (tutti configurabili)
    private final int[] arrivalRates;
    private final int poolSize;
    private final int numPh;
    private final int[][] weights;
    private final int rateDiv;
    private final int bphDiv;
    private final int wDiv;

    public ModelOris2(int[] arrivalRates, int poolSize, int numPh, int[][] weights, int rateDiv, int bphDiv, int wDiv) {
        this.arrivalRates = arrivalRates;
        this.poolSize = poolSize;
        this.numPh = numPh;
        this.weights = weights;
        this.rateDiv = rateDiv;
        this.bphDiv = bphDiv;
        this.wDiv = wDiv;
    }

    public PetriNet build() {
        PetriNet net = new PetriNet();

        // Pool iniziale
        Place pool = net.addPlace("Pool");

        // Postazioni e attività
        Place[] px = new Place[3];
        Place[] ax = new Place[3];
        for (int i = 0; i < 3; i++) {
            px[i] = net.addPlace("P" + (i + 1));
            ax[i] = net.addPlace("A" + (i + 1));
        }

        // Fasi intermedie
        Place[] phx = new Place[numPh];
        for (int i = 0; i < numPh; i++) {
            phx[i] = net.addPlace("Ph" + (i + 1));
        }

        // Transizioni di rilascio dal pool alle postazioni (con rate parametrico)
        for (int i = 0; i < 3; i++) {
            Transition release = net.addTransition("release" + (i + 1));
            BigDecimal rate = new BigDecimal(arrivalRates[i]).divide(BigDecimal.valueOf(rateDiv));
            release.addFeature(StochasticTransitionFeature.newExponentialInstance(rate, MarkingExpr.from("1", net)));
            net.addPrecondition(pool, release);
            net.addPostcondition(release, px[i]);
        }

        // Movimenti da P a A
        for (int i = 0; i < 3; i++) {
            Transition toA = net.addTransition("toA" + (i + 1));
            toA.addFeature(StochasticTransitionFeature.newExponentialInstance(BigDecimal.ONE, MarkingExpr.from("1", net)));
            net.addPrecondition(px[i], toA);
            net.addPostcondition(toA, ax[i]);
        }

        // Distribuzione pesata verso le fasi (matrice pesi)
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < numPh; j++) {
                String tName = "t" + (i + 1) + (j + 1);
                Transition t = net.addTransition(tName);
                t.addFeature(StochasticTransitionFeature.newExponentialInstance(BigDecimal.ONE, MarkingExpr.from("1", net)));
                net.addPrecondition(ax[i], t, weights[i][j]);
                net.addPostcondition(t, phx[j], weights[i][j]);
            }
        }

        // Uscita da ogni fase PhX verso il pool (rate proporzionale alla fase)
        for (int i = 0; i < numPh; i++) {
            String name = "t" + (i + 1);
            Transition tx = net.addTransition(name);
            BigDecimal rate = BigDecimal.valueOf(i + 1).divide(BigDecimal.valueOf(bphDiv));
            tx.addFeature(StochasticTransitionFeature.newExponentialInstance(rate, MarkingExpr.from("1", net)));
            net.addPrecondition(phx[i], tx, 1); 
            net.addPostcondition(tx, pool);
        }

        return net;
    }

    // Stato iniziale del sistema
    public Marking buildInitialMarking(PetriNet net) {
        Marking marking = new Marking();
        marking.setTokens(net.getPlace("Pool"), poolSize);
        return marking;
    }
}
