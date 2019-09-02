package com.benberi.cadesim.server.model.player.domain;

import com.benberi.cadesim.server.config.Constants;

public enum JobbersQuality {
	BASIC(
		0.03333 / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // fixRate              per tick
		0.26666 / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // bilgeFixRate         per tick
		40,                                                     // minBilgeForDamage
		0.11333 / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // moves                per tick
		0.3     / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // cannons              per tick
		0.01000 / (float)(1000 / Constants.SERVICE_LOOP_DELAY)  // full bilge move rate per tick
    ),
	ELITE(
		0.06666 / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // "
		0.46666 / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // "
		50,                                                     // "
		0.16000 / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // "
		0.4     / (float)(1000 / Constants.SERVICE_LOOP_DELAY), // "
		0.02000 / (float)(1000 / Constants.SERVICE_LOOP_DELAY)  // "
	);

    /**
     *  The fix amount per sec
     */
    private double fixRate;

    /**
     * The fix bilge amount per sec
     */
    private double bilgeFixRate;

    /**
     * Minimum damage for bilge to increase
     */
    private double minBilgeForDamamge;

    /**
     * Move generation per sec
     */
    private double movesRate;

    /**
     * Cannon generation per sec
     */
    private double cannonsRate;

    private double fullBilgeMoveRate;

    JobbersQuality(double fixRate, double bilgeFixRate, double minBilgeForDamage,  double movesRate, double cannonsRate, double fullBilgeMoveRate) {
        this.fixRate = fixRate;
        this.bilgeFixRate = bilgeFixRate;
        this.minBilgeForDamamge = minBilgeForDamage;
        this.movesRate = movesRate;
        this.cannonsRate = cannonsRate;
        this.fullBilgeMoveRate = fullBilgeMoveRate;
    }

    public double getFixRate() {
        return fixRate;
    }

    public double getCannonsPerTick() {
        return this.cannonsRate;
    }

    public double getBilgeFixPerTick() {
        return bilgeFixRate;
    }

    public double getMinDamageForBilge() {
        return minBilgeForDamamge;
    }

    public double getFullBilgeMovesPerTick() {
        return fullBilgeMoveRate;
    }

    public double getMovesPerTick() {
        return movesRate;
    }
}
