package place.placers.analytical;

import place.circuit.Circuit;
import place.interfaces.Logger;
import place.interfaces.Options;
import place.visual.PlacementVisualizer;

import java.util.List;
import java.util.Random;

public class GradientPlacerWLD extends GradientPlacer {

    public GradientPlacerWLD(Circuit circuit, Options options, Random random, Logger logger, PlacementVisualizer visualizer) {
        super(circuit, options, random, logger, visualizer);
    }

    @Override
    protected boolean isTimingDriven() {
        return false;
    }

    @Override
    protected void initializeIteration(int iteration) {
    	this.legalizer.initializeLegalizationAreas();
        if(iteration > 0) {
            this.anchorWeight += this.anchorWeightStep;
        }
    }

    @Override
    protected void updateLegalIfNeeded(int iteration) {
        // This placer always accepts the latest solution.
        // No cost has to be calculated, so this is faster.
        this.updateLegal(this.legalizer.getLegalX(), this.legalizer.getLegalY());
    }


    @Override
    protected void addStatTitlesGP(List<String> titles) {
        // Do nothing
    }

    @Override
    protected void addStats(List<String> stats) {
        // Do nothing
    }


    @Override
    public String getName() {
        return "Wirelength driven gradient descent placer";
    }
}