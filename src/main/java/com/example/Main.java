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
    public static void main(String[] args) {
        System.out.println("Main started");

        runAnalysis("risultati.txt", new int[]{2, 3, 1}, 8);

        System.gc();

        runAnalysis("risultati2.txt", new int[]{1, 1, 1}, 4);
    }

    public static void runAnalysis(String filename, int[] arrivalRates, int poolSize) {
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filename, false))) {

            int numPh = 4;
            int wDiv = 20;
            int[][] weights = {
                {1, 1, 1, 1},
                {1, 2, 3, 4},
                {4, 3, 2, 1}
            };

            int rateDiv = 20;
            int bphDiv = 5;
            ModelOris2 model = new ModelOris2(arrivalRates, poolSize, numPh, weights, rateDiv, bphDiv, wDiv);
            PetriNet net = model.build();
            Marking marking = model.buildInitialMarking(net);

            System.out.println("Running analysis on: " + filename);
            writer.write("Initial marking: " + marking + "\n");

            List<RewardRate> rewards = List.of(
                RewardRate.fromString("Pool"),
                RewardRate.fromString("P1"),
                RewardRate.fromString("A1"),
                RewardRate.fromString("P2"),
                RewardRate.fromString("A2"),
                RewardRate.fromString("P3"),
                RewardRate.fromString("A3"),
                RewardRate.fromString("Ph1"),
                RewardRate.fromString("Ph2"),
                RewardRate.fromString("Ph3"),
                RewardRate.fromString("Ph4"),
                RewardRate.fromString("If(Pool==0,1,0)"),
                RewardRate.fromString("If(A1>0,1,0)"),
                RewardRate.fromString("If(A2>0,1,0)"),
                RewardRate.fromString("If(A3>0,1,0)")
            );

            writer.write("Rewards:\n");
            for (RewardRate r : rewards) {
                writer.write(r.toString() + "\n");
            }

            // Transiente
            try {
                double step = 0.1;
                double execTime = 10.0;

                long start = System.currentTimeMillis();
                Pair<Map<Marking, Integer>, double[][]> result = GSPNTransient.builder()
                        .timePoints(0.0, execTime, step)
                        .build().compute(net, marking);
                long elapsed = System.currentTimeMillis() - start;
                TransientSolution<Marking, RewardRate> transientRewards = TransientSolution.computeRewards(false,
                        TransientSolution.fromArray(result.second(), step, result.first(), marking),
                        rewards.toArray(new RewardRate[0]));

                writer.write("\n--- Transient Reward Values ---\n");
                double[][][] solMatrix = transientRewards.getSolution();
                for (int i = 0; i < solMatrix.length; i++) {
                    writer.write("Time " + String.format("%.2f", (i * step)) + ": ");
                    for (int j = 0; j < solMatrix[i][0].length; j++) {
                        writer.write(String.format("%.5f ", solMatrix[i][0][j]));
                    }
                    writer.write("\n");
                }

                double avgPoolZero = Arrays.stream(solMatrix)
                        .mapToDouble(t -> t[0][11]) // reward If(Pool==0,1,0)
                        .average().orElse(0.0);
                writer.write("\nAverage transient value of If(Pool==0): " + String.format("%.5f", avgPoolZero) + "\n");

            } catch (Exception e) {
                writer.write("Error in transient:\n" + e.getMessage() + "\n");
                e.printStackTrace();
            }

            // Steady-state
            try {
                int estimatedStates = (int) Math.pow(poolSize + 1, numPh);
                writer.write("\nEstimated state space size: " + estimatedStates + "\n");

                if (estimatedStates <= 10000) {
                    Map<Marking, Double> rawSteady = GSPNSteadyState.builder().build().compute(net, marking);
                    Map<Marking, BigDecimal> converted = new HashMap<>();
                    for (Map.Entry<Marking, Double> entry : rawSteady.entrySet()) {
                        converted.put(entry.getKey(), BigDecimal.valueOf(entry.getValue()));
                    }
                    SteadyStateSolution<Marking> solution = new SteadyStateSolution<>(converted);
                    SteadyStateSolution<RewardRate> rewardsState = SteadyStateSolution.computeRewards(solution,
                            rewards.toArray(new RewardRate[0]));

                    writer.write("\n----- Steady-State Rewards -----\n");
                    for (Map.Entry<RewardRate, BigDecimal> entry : rewardsState.getSteadyState().entrySet()) {
                        writer.write(entry.getKey() + " : " + entry.getValue() + "\n");
                    }
                } else {
                    writer.write("Steady-state skipped: estimated state space too large (" + estimatedStates + " states)\n");

                }

            } catch (Exception e) {
                writer.write("Error in steady-state:\n" + e.getMessage() + "\n");
                e.printStackTrace();
            }

            writer.flush();

        } catch (IOException e) {
            System.err.println("Error while writing the file:");
            e.printStackTrace();
        }
    }
}