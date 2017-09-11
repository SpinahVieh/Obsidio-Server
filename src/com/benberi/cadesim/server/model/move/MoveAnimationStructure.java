package com.benberi.cadesim.server.model.move;

import com.benberi.cadesim.server.model.vessel.VesselMovementAnimation;

public class MoveAnimationStructure {

    /**
     * The turns
     */
    private MoveAnimationTurn[] turns = new MoveAnimationTurn[4];

    public MoveAnimationStructure() {
        reset();
    }

    /**
     * Gets the turn by index
     * @param index The turn index
     * @return The turn animation
     */
    public MoveAnimationTurn getTurn(int index) {
        return turns[index];
    }

    public int countFilledTurnSlots() {
        int count = 0;
        for (int slot = 0; slot < turns.length; slot++) {
            MoveAnimationTurn turn = turns[slot];
            if (turn.getAnimation() != VesselMovementAnimation.NO_ANIMATION ||
                    turn.getLeftShoots() > 0 || turn.getRightShoots() > 0) {
                count++;
            }
        }

        return count;
    }

    public void reset() {
        for (int i = 0; i < turns.length; i++) {
            turns[i] = new MoveAnimationTurn();
        }
    }
}
