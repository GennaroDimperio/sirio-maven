package com.example;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.oristool.models.gspn.GSPNSteadyState;
import org.oristool.models.gspn.GSPNTransient;
import org.oristool.models.stpn.RewardRate;
import org.oristool.models.stpn.SteadyStateSolution;
import org.oristool.models.stpn.TransientSolution;
import org.oristool.petrinet.Marking;
import org.oristool.petrinet.PetriNet;
import org.oristool.util.Pair;

public class Main {

    private static final String NL = System.lineSeparator();

    public static void main(String[] args) {
        AnalysisResult high = runAnalysis("risultati_high.txt", new int[]{8, 12, 4}, 8);
        AnalysisResult low  = runAnalysis("risultati_low.txt",  new int[]{2, 3, 1}, 4);

        // Low -> High
        int deltaPool = high.poolSize - low.poolSize;
        runTransitionAnalysis("transizione.txt", low.snapshot, low.poolSize, deltaPool);
    }


    public static AnalysisResult runAnalysis(String fileName, int[] rates, int poolTokens) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(fileName, false))) {

            // Parametrs 
            int numPh = 4;
            int wDiv  = 20;
            int[][] W = { {1,1,1,1}, {1,2,3,4}, {4,3,2,1} };
            int rateDiv = 20;   
            int bphDiv  = 5;

            ModelOris2 model = new ModelOris2(rates, poolTokens, numPh, W, rateDiv, bphDiv, wDiv);
            PetriNet net   = model.build();
            Marking  m0    = model.buildInitialMarking(net);

            w.write("Initial marking: " + m0 + NL + NL);

            List<RewardRate> rewards = List.of(
            RewardRate.fromString("Pool"),
            RewardRate.fromString("Ph1"),
            RewardRate.fromString("Ph2"),
            RewardRate.fromString("Ph3"),
            RewardRate.fromString("Ph4"),
            RewardRate.fromString("A1"),
            RewardRate.fromString("A2"),
            RewardRate.fromString("A3"),

            // QoS metrics
            RewardRate.fromString("If(Pool==0,1,0)"),                     
            RewardRate.fromString("If(A1>0 || A2>0 || A3>0,1,0)"),       
            RewardRate.fromString("Ph1+Ph2+Ph3+Ph4")                     
            );

            // Transient analysis
            double[] snapshot = new double[5];
            try {
                double step = 0.1, time = 40.0;
                Pair<Map<Marking,Integer>, double[][]> res = GSPNTransient.builder()
                        .timePoints(0.0, time, step)
                        .build().compute(net, m0);

                TransientSolution<Marking,RewardRate> ts = TransientSolution.computeRewards(
                        false,
                        TransientSolution.fromArray(res.second(), step, res.first(), m0),
                        rewards.toArray(new RewardRate[0]));

                w.write("--- Transient rewards ---" + NL);
                w.write("t ");
                for (RewardRate r : rewards) w.write(r + " ");
                w.write(NL);

                double[][][] M = ts.getSolution();
                for (int i = 0; i < M.length; i++) {
                    w.write(String.format("%.1f ", i * step));
                    for (double v : M[i][0]) w.write(String.format("%.5f ", v));
                    w.write(NL);
                }
                w.write(NL);

                snapshot = Arrays.copyOf(M[M.length - 1][0], 5);

            } catch (Exception ex) {
                w.write("Transient ERROR: " + ex.getMessage() + NL + NL);
            }

            // Steady-state analysis
            try {
                Map<Marking, Double> raw = GSPNSteadyState.builder().build().compute(net, m0);
                Map<Marking, BigDecimal> bd = new HashMap<>();
                raw.forEach((k,v) -> bd.put(k, BigDecimal.valueOf(v)));

                SteadyStateSolution<Marking> ss  = new SteadyStateSolution<>(bd);
                SteadyStateSolution<RewardRate> ssR = SteadyStateSolution.computeRewards(
                        ss, rewards.toArray(new RewardRate[0]));

                w.write("--- Steady-state rewards ---" + NL);
                for (RewardRate r : rewards) {
                    w.write(r + " : " + ssR.getSteadyState().get(r) + NL);
                }
                w.write(NL);
            } catch (Exception ex) {
                w.write("Steady-state ERROR: " + ex.getMessage() + NL);
            }

            w.flush();
            return new AnalysisResult(snapshot, poolTokens);
        } catch (IOException ioe) {
            ioe.printStackTrace();
            return null;
        }
    }


    public static void runTransitionAnalysis(String fileName, double[] probsLow,
                                             int lowPoolTokens, int deltaPool) {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(fileName, false))) {

            PetriNet net = ModelOris2.getThirdModel(probsLow);
            Marking  m0  = ModelOris2.getThirdModelInitialMarking(net, probsLow, lowPoolTokens, deltaPool);

            double step = 0.1, time = 40.0;
            Pair<Map<Marking,Integer>, double[][]> res = GSPNTransient.builder()
                    .timePoints(0.0, time, step)
                    .build().compute(net, m0);

            List<RewardRate> rewards = List.of(
            RewardRate.fromString("Pool"),
            RewardRate.fromString("Ph1"),
            RewardRate.fromString("Ph2"),
            RewardRate.fromString("Ph3"),
            RewardRate.fromString("Ph4"),
            RewardRate.fromString("If(Pool==0,1,0)"),
            RewardRate.fromString("Ph1+Ph2+Ph3+Ph4") 
            );

            TransientSolution<Marking,RewardRate> ts = TransientSolution.computeRewards(
                false,
                TransientSolution.fromArray(res.second(), step, res.first(), m0),
                rewards.toArray(new RewardRate[0]));

        w.write("--- Transient rewards (transition) ---" + NL);
        w.write("t ");
        for (RewardRate r : rewards) w.write(r + " ");
        w.write(NL);

        double[][][] M = ts.getSolution();
        for (int i = 0; i < M.length; i++) {
            w.write(String.format("%.1f ", i * step));
            for (double v : M[i][0]) w.write(String.format("%.5f ", v));
            w.write(NL);
        }
                    w.flush();
                } catch (IOException ioe) {
                    ioe.printStackTrace();
                }
    }

    // AnalysisResult class to hold the results of the analysis
    public static class AnalysisResult {
        public final double[] snapshot;
        public final int      poolSize;
        public AnalysisResult(double[] snap, int pool) { this.snapshot = snap; this.poolSize = pool; }
    }
}
